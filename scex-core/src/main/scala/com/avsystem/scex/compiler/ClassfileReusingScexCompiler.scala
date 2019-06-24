package com.avsystem.scex.compiler

import java.io.{IOException, OutputStreamWriter}

import com.avsystem.scex.compiler.ClassfileReusingScexCompiler.GlobalCacheVersion
import com.google.common.cache.{Cache, CacheBuilder}

import scala.collection.mutable
import scala.reflect.io.AbstractFile
import scala.tools.nsc.io.JFile
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.util.ClassPath
import scala.tools.nsc.{Phase, Settings}
import scala.util.Try

object ClassfileReusingScexCompiler {
  final val GlobalCacheVersion = 2
}

/**
  * An adaptation of ScexCompiler which compiles non-shared classes to disk instead of memory (assuming that classfile
  * directory is configured). Class files are never being deleted automatically and thus are reused even if the entire
  * process is restarted.
  *
  * The decision about need for recompilation is made based on signature file generated every time an expression is compiled
  * Signature file contains typed and erased (bytecode) signatures of all symbols (methods, fields, etc.) used by
  * the expression. In order to reuse previously compiled classfiles, all symbols listed in the signature file must
  * exist and have the same signature they had at the time the expression was originally compiled.
  *
  * This strategy is unfortunately unable to detect some binary compatibility breaches which are source compatible:
  * <ul>
  * <li>Changes to any implicit symbols visible inside expressions (e.g. adding new implicit) that could
  * change the way some implicit parameter or conversion is resolved.</li>
  * <li>Adding or removing overloaded variants to methods used in expressions which could cause the overloaded
  * variant used by expression to change.</li>
  * </ul>
  *
  * Created: 21-10-2014
  * Author: ghik
  */
trait ClassfileReusingScexCompiler extends ScexCompiler {

  import com.avsystem.scex.util.CommonUtils._

  private val sigHeader = "SIGNATURES:\n"
  private val logger = createLogger[ClassfileReusingScexCompiler]

  private class State(val classfileDir: AbstractFile) {
    def adjustCompilerSettings(settings: Settings): Settings =
      if (!ClassPath.split(settings.classpath.value).contains(classfileDir.path)) {
        val adjustedSettings = new ScexSettings
        settings.copyInto(adjustedSettings)
        adjustedSettings.classpath.append(classfileDir.path)
        adjustedSettings
      } else settings

    val nonSharedClassLoaders: Cache[String, ScexClassLoader] =
      CacheBuilder.newBuilder.weakValues.build[String, ScexClassLoader]
  }

  private var _stateOpt: Option[State] = _

  override protected def compilerSettings: Settings =
    stateOpt.map(_.adjustCompilerSettings(super.compilerSettings)).getOrElse(super.compilerSettings)

  private def stateOpt = {
    if (_stateOpt == null) {
      setup()
    }
    _stateOpt
  }

  private def ensureDirectoryExists(file: JFile): Unit = {
    // I heard some rumors about race conditions in `mkdirs`...
    var i = 0
    while (i < 100 && !file.exists() && !file.mkdirs()) {
      Thread.sleep(50)
      i += 1
    }
    if (!file.exists()) {
      throw new IOException(s"Failed to create directory $file")
    }
  }

  override protected def createNonSharedClassLoader(sourceFile: ScexSourceFile): ScexClassLoader =
    stateOpt.map { state =>
      import state._

      val sourceName = sourceFile.file.name

      def createClassLoader = {
        val dir = classfileDir.subdirectoryNamed(sourceName)
        ensureDirectoryExists(dir.file)
        new ScexClassLoader(dir, getSharedClassLoader)
      }

      nonSharedClassLoaders.get(sourceName, callable(createClassLoader))
    } getOrElse super.createNonSharedClassLoader(sourceFile)

  override protected def setup(): Unit = {
    _stateOpt = settings.resolvedClassfileDir.map(new State(_))
    stateOpt.foreach { state =>
      val currentVersion = GlobalCacheVersion.toString + "." + settings.backwardsCompatCacheVersion.value
      val versionFileName = "cacheVersion"
      if (state.classfileDir.exists) {
        val savedVersion = Option(state.classfileDir.lookupName(versionFileName, directory = false))
          .flatMap(versionFile => Try(new String(versionFile.toCharArray)).toOption)
          .getOrElse("0")

        if (savedVersion != currentVersion) {
          logger.info("Classfile cache version changed, deleting classfile directory")
          state.classfileDir.delete()
        }
      }
      ensureDirectoryExists(state.classfileDir.file)
      val os = state.classfileDir.fileNamed(versionFileName).output
      try os.write(currentVersion.getBytes) finally os.close()
    }
    super.setup()
  }

  override protected def runCompiler(global: ScexGlobal, sourceFile: ScexSourceFile): Unit = {
    import global._
    new Run

    def isValid(signature: String): Boolean = signature.startsWith(sigHeader) &&
      signature.stripPrefix(sigHeader).split("\\n{2,}").iterator.map(_.trim).filter(!_.isEmpty).forall { sig =>
        val Array(typedSig, erasedSig) = sig.split("\n")
        val Array(fullName, _) = typedSig.split(":", 2)

        def symbolsWithName(owner: Symbol, nameParts: List[String]): Iterator[Symbol] =
          nameParts match {
            case namePart :: rest if owner.isClass || owner.isModule =>
              val ownerType = owner.toType
              val members = Iterator(ownerType.member(TypeName(namePart))) ++ symAlternatives(ownerType.member(TermName(namePart)))
              members.filter(_ != NoSymbol).flatMap(symbolsWithName(_, rest))
            case Nil => Iterator(owner)
            case _ => Iterator.empty
          }

        symbolsWithName(RootClass, fullName.split("\\.").toList)
          .filter(_.isTerm).flatMap(s => s :: s.overrides)
          .map(s => (typedSignature(global)(s.asTerm), erasedSignature(global)(s.asTerm)))
          .contains((typedSig, erasedSig))
      }

    val sigFileName = sourceFile.file.name + ".sig"
    val optimizedRun = for {
      state <- stateOpt
      outDir <- global.settings.outputDirs.getSingleOutput
      sigFile <- Option(outDir.lookupName(sigFileName, directory = false)) if isValid(new String(sigFile.toCharArray))
    } yield {
      logger.debug(s"Expression source file ${sourceFile.file.name} has already been compiled and bytecode is compatible.")
    }

    // If we're about to recompile expression, then we need to make the compiler forget about the old, cached one that
    // we've just inspected. Otherwise the compiler will see a conflict and issue an error (at least since 2.12.5):
    // "package x contains object and package with same name: x"
    val rootScope = RootClass.info.decls
    val exprPkgSym = rootScope.lookup(TermName(sourceFile.file.name))
    rootScope.unlink(exprPkgSym)

    optimizedRun getOrElse super.runCompiler(global, sourceFile)
  }

  private def erasedSignature(global: ScexGlobal)(sym: global.TermSymbol) = try {
    import global._
    if (sym.isClassConstructor) {
      val constr = constructorToJava(sym.asMethod)
      val paramsSignature: String = constr.getParameterTypes.map(_.getName).mkString("(", ",", ")")
      constr.getDeclaringClass.getName + paramsSignature
    } else if (sym.isMethod) {
      val meth = methodToJava(sym.asMethod)
      val paramsSignature: String = meth.getParameterTypes.map(_.getName).mkString("(", ",", ")")
      meth.getDeclaringClass.getName + "." + meth.getName + paramsSignature + meth.getReturnType.getName
    } else if (isJavaField(sym)) {
      val fld = fieldToJava(sym)
      fld.getDeclaringClass.getName + "." + fld.getName + ":" + fld.getType.getName
    } else if (sym.isModule) {
      typeToJavaClass(sym.toType).getName
    } else "<none>"
  } catch {
    case _: NoSuchMethodException | _: NoSuchFieldException | _: ClassNotFoundException =>
      "<none>"
  }

  private def typedSignature(global: ScexGlobal)(sym: global.TermSymbol) =
    sym.fullName + ":" + sym.info.paramLists.map(_.map(_.typeSignature.toString()).mkString("(", ",", ")")).mkString +
      sym.typeSignature.finalResultType.toString()

  private class SignatureGenerator(val global: ScexGlobal) extends Plugin { plugin =>

    import global._

    val name = "signatureGenerator"
    val components: List[PluginComponent] = List(genSignature, saveSignature)
    val description = "SCEX signature generator"

    private val sigs = new mutable.WeakHashMap[CompilationUnit, String]

    private abstract class BaseComponent(runsAfterPhase: String, val phaseName: String) extends PluginComponent {
      val global: plugin.global.type = plugin.global
      val runsAfter = List(runsAfterPhase)

      def newPhase(prev: Phase): StdPhase = new StdPhase(prev) {
        override def apply(unit: CompilationUnit): Unit =
          applyComponentPhase(unit)
      }

      def applyComponentPhase(unit: CompilationUnit): Unit
    }

    private object genSignature extends BaseComponent("typer", "genSignature") {
      def applyComponentPhase(unit: CompilationUnit): Unit = for {
        state <- stateOpt
        outDir <- global.settings.outputDirs.getSingleOutput
      } {
        unit.body.find(_.hasAttachment[ExpressionTreeAttachment.type]).foreach { tree =>
          val signatures = new mutable.HashSet[String]

          tree.foreach { t =>
            val s = t.symbol
            if (s != null && s.isTerm && !s.hasPackageFlag && s.sourceFile != unit.source.file) {
              signatures += typedSignature(global)(s.asTerm) + "\n" + erasedSignature(global)(s.asTerm)
            }
          }

          sigs(unit) = signatures.toList.sorted.mkString(sigHeader, "\n\n", "\n")
        }
      }
    }

    // signature is saved after full compilation when we're sure that there were no compilation errors
    private object saveSignature extends BaseComponent("jvm", "saveSignature") {
      def applyComponentPhase(unit: CompilationUnit): Unit = for {
        state <- stateOpt
        outDir <- global.settings.outputDirs.getSingleOutput
        sig <- sigs.get(unit)
      } {
        logger.debug(s"Saving source and signatures file for ${unit.source.file.name}:\n$sig")
        val sourceWriter = new OutputStreamWriter(outDir.fileNamed(unit.source.file.name + ".scala").output)
        try sourceWriter.write(unit.source.content) finally sourceWriter.close()
        val sigOutputStream = outDir.fileNamed(unit.source.file.name + ".sig").output
        try sigOutputStream.write(sig.getBytes) finally sigOutputStream.close()
        sigs.remove(unit)
      }
    }

  }

  override protected def loadCompilerPlugins(global: ScexGlobal): List[Plugin] =
    new SignatureGenerator(global) :: super.loadCompilerPlugins(global)
}
