package com.avsystem.scex

import java.{util => ju, lang => jl}
import scala.reflect.runtime.{universe => ru}
import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.AbstractFileClassLoader
import scala.reflect.ClassTag
import com.avsystem.scex.validation.ExpressionValidator
import scala.language.existentials
import com.google.common.cache.{RemovalNotification, CacheBuilder}
import scala.collection.mutable
import com.avsystem.scex.Utils._
import org.apache.commons.lang.StringEscapeUtils
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.reporters.AbstractReporter
import scala.reflect.internal.util.{BatchSourceFile, Position}
import CacheImplicits._
import scala.collection.mutable.ListBuffer
import scala.ref.WeakReference
import scala.Predef._
import scala.Predef.Function
import scala.Some
import scala.tools.reflect.ReflectGlobal

import ExpressionCompiler._

/**
 * Central class for expression compilation. Encapsulates Scala compiler, so it is
 * VERY heavy. Thread safe with compilation synchronized.
 */
class ExpressionCompiler {

  private val virtualDirectory = new VirtualDirectory("(scex)", None)

  private val settings = new Settings
  settings.usejavacp.value = true
  settings.exposeEmptyPackage.value = true
  settings.outputDirs.setSingleOutput(virtualDirectory)

  private object reporter extends AbstractReporter {
    private val errorsBuffer = new ListBuffer[CompileError]

    def compileErrors = errorsBuffer.result()

    val settings: Settings = ExpressionCompiler.this.settings

    def display(pos: Position, msg: String, severity: Severity) {
      if (severity == ERROR) {
        errorsBuffer += CompileError(pos.lineContent, if (pos.isDefined) pos.column else 1, msg)
      }
    }

    def displayPrompt() {}

    override def reset() {
      super.reset()
      errorsBuffer.clear()
    }
  }

  private val global = new ReflectGlobal(settings, reporter, getClass.getClassLoader)
  private val runtimeMirror = ru.runtimeMirror(getClass.getClassLoader)

  private var classLoader = new AbstractFileClassLoader(virtualDirectory, getClass.getClassLoader)

  //TODO: profiles, cache expiration, classLoader resetting, validator compilation

  private val expressionCache =
    CacheBuilder.newBuilder.removalListener(onExprRemove _).build[ExpressionDef, RawExpression](loadCompiledRawExpression _)

  private val expressionPackageNamesCache =
    CacheBuilder.newBuilder.removalListener(onExprImplRemove _).build[ExpressionDef, String](compileExpression _)

  private def onExprRemove(notification: RemovalNotification[ExpressionDef, RawExpression]) {
    expressionPackageNamesCache.invalidate(notification.getKey)
  }

  private def onExprImplRemove(notification: RemovalNotification[ExpressionDef, String]) {
    virtualDirectory.lookupPath(notification.getValue.replaceAllLiterally(".", "/"), directory = true).delete()
  }

  private def loadCompiledRawExpression(exprDef: ExpressionDef): RawExpression = synchronized {
    val className = s"${expressionPackageNamesCache.get(exprDef)}.$expressionClassName"
    Class.forName(className, true, classLoader).newInstance.asInstanceOf[RawExpression]
  }

  private var idx = 0

  private def newExpressionPackage() = {
    idx += 1
    "_scex_expr$" + idx
  }

  private def newProfilePackage() {
    idx += 1
    "_scex_profile$" + idx
  }

  private def compileExpression(exprDef: ExpressionDef): String = synchronized {
    def erasedType[A](clazz: Class[A]) =
      runtimeMirror.classSymbol(clazz).toType.erasure

    val contextType = erasedType(exprDef.contextClass).toString
    val resultType = erasedType(exprDef.resultClass).toString

    val header = Option(exprDef.profile.expressionHeader).getOrElse("")

    val pkgName = newExpressionPackage()
    val profileObjectName = null

    val codeToCompile =
      s"""
        |package $pkgName
        |
        |class $expressionClassName extends ($contextType => $resultType) {
        |  def apply(__ctx: $contextType): $resultType = {
        |    $header
        |    /* import profile object */
        |    import __ctx._
        |    com.avsystem.scex.validation.ExpressionValidator.validate(${exprDef.expression})
        |  }
        |}
        |
      """.stripMargin

    val run = new global.Run

    ExpressionValidator.profile.withValue(exprDef.profile) {
      run.compileSources(List(new BatchSourceFile("(scex expression)", codeToCompile)))
    }

    if (reporter.hasErrors) {
      throw new CompilationFailedException(codeToCompile, reporter.compileErrors)
    }

    pkgName
  }

  private def clazzOf[T: ClassTag] = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]

  def getCompiledStringExpression[C <: AnyRef : ClassTag](
    profile: ExpressionProfile,
    expression: String): Expression[C, String] = {

    getCompiledStringExpression(profile, expression, clazzOf[C])
  }

  def getCompiledStringExpression[C <: AnyRef](
    profile: ExpressionProfile,
    expression: String,
    contextClass: Class[C]): Expression[C, String] = {

    getCompiledExpression(profile, "s\"\"\"" + StringEscapeUtils.escapeJava(expression) + "\"\"\"", contextClass, classOf[String])
  }

  def getCompiledExpression[C <: AnyRef : ClassTag, R <: AnyRef : ClassTag](
    profile: ExpressionProfile,
    expression: String): Expression[C, R] = {

    getCompiledExpression[C, R](profile, expression, clazzOf[C], clazzOf[R])
  }

  /**
   * Returns compiled expression ready to be evaluated. Returned expression is actually a proxy that
   * looks up ExpressionCompiler's cache on every access (evaluation).
   *
   * @param profile expression profile to compile this expression with
   * @param expression the expression
   * @param contextClass expression context class
   * @param resultClass expression result class
   */
  def getCompiledExpression[C <: AnyRef, R <: AnyRef](
    profile: ExpressionProfile,
    expression: String,
    contextClass: Class[C],
    resultClass: Class[R]): Expression[C, R] = {

    require(profile != null, "Profile cannot be null")
    require(expression != null, "Expression cannot be null")
    require(contextClass != null, "Context class cannot be null")
    require(resultClass != null, "Result class cannot be null")

    val exprDef = ExpressionDef(profile, expression, contextClass, resultClass)

    // return wrapper over a weak reference to actual expression
    // this allows the actual expression to be GCed when ExpressionCompiler is doing cleanup
    // without which the classloader will not be GCed and its classes unloaded
    new Expression[C, R] {
      private var rawExpressionRef = new WeakReference(loadRawExpression)

      private def loadRawExpression = expressionCache.get(exprDef)

      private def rawExpression = rawExpressionRef.get match {
        case Some(expr) => expr
        case None =>
          val result = loadRawExpression
          rawExpressionRef = new WeakReference(result)
          result
      }

      def apply(context: C): R =
        rawExpression.asInstanceOf[C => R].apply(context)
    }
  }

  private def generateProfileObject(ident: String, profile: ExpressionProfile): String = {
    val sb = new StringBuilder
    profile.accessValidator.referencedClasses foreach { clazz =>
      sb ++= javaGetterAdaptersCache.get(clazz)
    }
    val wrappers = sb.mkString

    s"object $ident { $wrappers }"
  }
}

object ExpressionCompiler {

  case class CompileError(line: String, column: Int, msg: String) {
    override def toString = s"$msg:\n${line.stripLineEnd}:\n${" " * (column - 1)}^"
  }

  case class CompilationFailedException(source: String, errors: List[CompileError])
    extends Exception(s"Compilation failed with ${errors.size} errors:\n${errors.mkString("\n")}")

  private val expressionClassName = "Expression"

  private val javaGetterAdaptersCache =
    CacheBuilder.newBuilder.build(generateJavaGetterAdapter: Class[_] => String)

  private case class ExpressionDef(
    profile: ExpressionProfile,
    expression: String,
    contextClass: Class[_],
    resultClass: Class[_]) {
  }

  private type RawExpression = Function[_, _]

  // extractor object for java getters
  private object JavaGetter {
    val getterPattern = "get([A-Z][a-z0-9_]*)+".r
    val booleanGetterPattern = "is([A-Z][a-z0-9_]*)+".r

    def unapply(symbol: ru.Symbol): Option[(String, Boolean)] =
      if (isJavaParameterlessMethod(symbol)) {

        def uncapitalize(str: String) =
          str.head.toLower + str.tail
        def isBoolOrBoolean(tpe: ru.Type) =
          tpe =:= ru.typeOf[Boolean] || tpe =:= ru.typeOf[jl.Boolean]

        symbol.name.toString match {
          case getterPattern(capitalizedName) =>
            Some((uncapitalize(capitalizedName), false))
          case booleanGetterPattern(capitalizedName) if isBoolOrBoolean(symbol.asMethod.returnType) =>
            Some((uncapitalize(capitalizedName), true))
          case _ => None
        }

      } else
        None
  }

  /**
   * Generates code of implicit view for given Java class that adds Scala-style getters
   * forwarding to existing Java-style getters of given class.
   */
  private def generateJavaGetterAdapter(clazz: Class[_]): String = {
    val classSymbol = ru.runtimeMirror(getClass.getClassLoader).classSymbol(clazz)

    if (!classSymbol.isJava) {
      return ""
    }

    val tpe = classSymbol.toType

    // generate scala getters
    val sb = new StringBuilder
    val alreadyWrapped = new mutable.HashSet[String]
    tpe.members.sorted.foreach {
      symbol =>
        symbol match {
          case JavaGetter(propName, booleanIsGetter) if !alreadyWrapped.contains(propName) =>
            alreadyWrapped += propName
            val annot = if (booleanIsGetter) "@com.avsystem.scex.BooleanIsGetter" else ""
            sb ++= s"$annot def `$propName` = wrapped.${symbol.name}\n"
          case _ => ()
        }
    }
    val propDefs = sb.mkString

    if (propDefs.isEmpty) {
      return ""
    }

    // generate class code
    val rawName = tpe.typeConstructor.toString
    val adapterName = "Adapter_" + rawName.replaceAll("\\.", "_")

    val typeParams = classSymbol.typeParams
    val genericTypes = if (typeParams.nonEmpty) typeParams.map(_.name).mkString("[", ",", "]") else ""

    val genericTypesWithBounds = if (typeParams.nonEmpty) {
      typeParams.map {
        param =>
          param.name.toString + param.typeSignature
      } mkString("[", ",", "]")
    } else ""

    s"""
      |implicit class $adapterName $genericTypesWithBounds
      |  (val wrapped: $rawName $genericTypes)
      |  extends AnyVal with com.avsystem.scex.JavaGettersAdapter {
      |$propDefs
      |}
      |
    """.stripMargin

  }
}