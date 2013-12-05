package com.avsystem.scex
package compiler.presentation

import com.google.common.cache.CacheBuilder
import compiler.ScexCompiler.CompileError
import compiler.{ExpressionDef, ExpressionMacroProcessor, ScexCompiler}
import java.{util => ju, lang => jl}
import scala.reflect.internal.util.{SourceFile, BatchSourceFile}
import scala.reflect.runtime.universe.TypeTag
import scala.tools.nsc.interactive.{Global => IGlobal}
import util.CommonUtils._
import validation.ValidationContext

trait ScexPresentationCompiler extends ScexCompiler {
  compiler =>

  import ScexPresentationCompiler.{Member => SMember, Param, Completion}

  object lock

  private var reporter: Reporter = _
  private var global: IGlobal = _

  private val typeCompletionCache =
    CacheBuilder.newBuilder.weakKeys.build[TypeWrapper, Completion]

  private def init(): Unit = {
    reporter = new Reporter(settings)
    global = new IGlobal(settings, reporter)
  }

  init()

  private def newInteractiveExpressionPackage() =
    newPackageName("_scex_interactive_expr")

  def getOrThrow[T](resp: IGlobal#Response[T]) = resp.get match {
    case Left(res) => res
    case Right(t) => throw t
  }

  trait Completer {
    def getErrors(expression: String): List[CompileError]

    def getScopeCompletion(expression: String, position: Int): Completion

    def getTypeCompletion(expression: String, position: Int): Completion
  }

  private class CompleterImpl(
    profile: ExpressionProfile,
    template: Boolean,
    setter: Boolean,
    header: String,
    contextType: String,
    rootObjectClass: Class[_],
    resultType: String) extends Completer {

    require(profile != null, "Profile cannot be null")
    require(contextType != null, "Context type cannot be null")
    require(rootObjectClass != null, "Root object class cannot be null")
    require(resultType != null, "Result type cannot be null")

    val pkgName = newInteractiveExpressionPackage()

    private def withGlobal[T](code: IGlobal => T) = lock.synchronized {
      val global = compiler.global
      getOrThrow(global.askForResponse(() => ExpressionMacroProcessor.profileVar.value = profile))
      val result = code(global)
      getOrThrow(global.askForResponse(() => ExpressionMacroProcessor.profileVar.value = null))
      reporter.reset()
      result
    }

    private def getContextTpe(global: IGlobal)(tree: global.Tree): global.Type = {
      import global._

      getOrThrow(askForResponse {
        () =>
          val PackageDef(_, List(ClassDef(_, _, _, Template(List(_, expressionParent, _), _, _)))) = tree
          val TypeRef(_, _, List(contextTpe, _)) = expressionParent.tpe
          contextTpe
      })
    }

    private def translateMember(vc: ValidationContext {val universe: IGlobal})(member: vc.universe.Member) = {
      import vc._
      import vc.universe._

      def symbolToParam(sym: Symbol) =
        Param(sym.decodedName, sym.typeSignature.toString())

      SMember(member.sym.decodedName,
        paramsOf(member.tpe).map(_.map(symbolToParam)),
        resultTypeOf(member.tpe).toString(),
        member.sym.isImplicit)
    }

    def getErrors(expression: String) = withGlobal {
      global =>
        val exprDef = ExpressionDef(profile, template, setter, expression, header, rootObjectClass, contextType, resultType)
        val (code, _) = expressionCode(exprDef, pkgName)
        val response = new global.Response[global.Tree]
        global.askLoadedTyped(new BatchSourceFile(pkgName, code), response)
        getOrThrow(response)
        reporter.compileErrors()
    }

    def getScopeCompletion(expression: String, position: Int): Completion = withGlobal { global =>
      import global.{sourceFile => _, position => _, _}
      val symbolValidator = profile.symbolValidator

      val exprDef = ExpressionDef(profile, template, setter, expression, header, rootObjectClass, contextType, resultType)
      val (code, offset) = expressionCode(exprDef, pkgName)
      val sourceFile = new BatchSourceFile(pkgName, code)

      val treeResponse = new Response[Tree]
      askLoadedTyped(sourceFile, treeResponse)
      val sourceTree = getOrThrow(treeResponse)
      val errors = compiler.reporter.compileErrors()

      val vc = ValidationContext(global)(getContextTpe(global)(sourceTree))
      import vc._

      def accessFromScopeMember(m: ScopeMember) = {
        // static module will be allowed by default only when at least one of its members is allowed
        val staticAccessAllowedByDefault = isStaticModule(m.sym) && symbolValidator.referencesModuleMember(m.sym.fullName)
        extractAccess(Select(m.viaImport, m.sym), staticAccessAllowedByDefault)
      }

      val response = new Response[List[Member]]
      askScopeCompletion(sourceFile.position(offset + position), response)
      val scope = getOrThrow(response)

      val members = getOrThrow(askForResponse {
        () =>
          scope.collect {
            case member@ScopeMember(sym, _, _, viaImport)
              if viaImport != EmptyTree && sym.isTerm && !sym.isPackage &&
                (!isScexSynthetic(sym) || (isExpressionUtil(sym) && !isExpressionUtilObject(sym))) =>
              member
          } filter {
            m =>
              symbolValidator.validateMemberAccess(vc)(accessFromScopeMember(m)).deniedAccesses.isEmpty
          } map translateMember(vc)
      })

      val deleteResponse = new Response[Unit]
      askFilesDeleted(List(sourceFile), deleteResponse)
      getOrThrow(deleteResponse)

      Completion(members, errors)
    }

    def getTypeCompletion(expression: String, position: Int): Completion = withGlobal { global =>
      import global.{sourceFile => _, position => _, _}
      val symbolValidator = profile.symbolValidator

      val exprDef = ExpressionDef(profile, template, setter, expression, header, rootObjectClass, contextType, resultType)
      val (code, offset) = expressionCode(exprDef, pkgName)
      val sourceFile = new BatchSourceFile(pkgName, code)
      val pos = sourceFile.position(offset + position)

      val treeResponse = new Response[Tree]
      askLoadedTyped(sourceFile, treeResponse)
      val sourceTree = getOrThrow(treeResponse)
      val errors = compiler.reporter.compileErrors()

      val vc = ValidationContext(global)(getContextTpe(global)(sourceTree))
      import vc._

      val typedTreeResponse = new Response[Tree]
      askTypeAt(pos, typedTreeResponse)
      val typedTree = getOrThrow(typedTreeResponse)

      // Hack: implicit conversions (conversion tree and implicit type) must be obtained manually from the
      // compiler because TypeMember contains only the implicit conversion symbol.
      // Code fragments from [[scala.tools.nsc.interactive.Global#typeMembers]] are used here.

      val context = doLocateContext(pos)

      // this code is taken from [[scala.tools.nsc.interactive.Global#typeMembers]]
      val (tree, ownerTpe) = getOrThrow(askForResponse {
        () =>
          var tree = typedTree

          tree match {
            case Select(qual, name) if tree.tpe == ErrorType => tree = qual
            case _ =>
          }

          if (tree.tpe == null)
            tree = analyzer.newTyper(context).typedQualifier(tree)

          val pre = stabilizedType(tree)

          val ownerTpe = tree.tpe match {
            case analyzer.ImportType(expr) => expr.tpe
            case null => pre
            case MethodType(List(), rtpe) => rtpe
            case _ => tree.tpe
          }

          (tree, ownerTpe)
      })

      def isValueType(tpe: Type): Boolean = tpe match {
        case TypeRef(_, _, _) |
             ConstantType(_) |
             SingleType(_, _) |
             RefinedType(_, _) |
             ExistentialType(_, _) |
             ThisType(_) |
             SuperType(_, _) |
             WildcardType |
             BoundedWildcardType(_) =>
          true
        case MethodType(_, resultTpe) =>
          isValueType(resultTpe)
        case NullaryMethodType(resultTpe) =>
          isValueType(resultTpe)
        case AnnotatedType(_, underlying, _) =>
          isValueType(underlying)
        case _ =>
          false
      }

      val result = typeCompletionCache.get(TypeWrapper(global)(ownerTpe), callable {
        if (isValueType(ownerTpe)) {

          val response = new Response[List[Member]]
          askTypeCompletion(pos, response)
          val scope = getOrThrow(response)

          getOrThrow(askForResponse { () =>

          // code based on [[scala.tools.nsc.interactive.Global#typeMembers]]
            val applicableViews: List[analyzer.SearchResult] =
              if (ownerTpe == null || ownerTpe.isErroneous) Nil
              else new analyzer.ImplicitSearch(
                tree, definitions.functionType(List(ownerTpe), definitions.AnyClass.tpe), isView = true,
                context.makeImplicit(reportAmbiguousErrors = false), NoPosition).allImplicits

            val viewsMap: Map[Symbol, (Tree, Type)] = applicableViews.map {
              searchResult =>
                val implicitTpe = analyzer.newTyper(context.makeImplicit(reportAmbiguousErrors = false))
                  .typed(Apply(searchResult.tree, List(tree)) setPos tree.pos)
                  .onTypeError(EmptyTree).tpe

                (searchResult.tree.symbol, (searchResult.tree, implicitTpe))
            }.toMap

            def fakeIdentWithAttrsOf(tree: Tree) = {
              val symbol = if(tree.symbol != null) tree.symbol else NoSymbol
              val tpe = if(tree.tpe != null) tree.tpe else NoType
              Ident(nme.EMPTY).setPos(tree.pos).setSymbol(symbol).setType(tpe)
            }

            // validation of members from type completion
            def accessFromTypeMember(member: TypeMember) =
              if (!member.implicitlyAdded)
                extractAccess(Select(fakeIdentWithAttrsOf(tree), member.sym))
              else {
                val (implicitTree, implicitTpe) = viewsMap(member.viaView)
                extractAccess(Select(Apply(implicitTree, List(fakeIdentWithAttrsOf(tree))).setSymbol(member.viaView).setType(implicitTpe), member.sym))
              }

            val members = scope.collect {
              case member: TypeMember
                if member.sym.isTerm && !member.sym.isConstructor && !isAdapterWrappedMember(member.sym) =>
                member
            } filter { member =>
              symbolValidator.validateMemberAccess(vc)(accessFromTypeMember(member)).deniedAccesses.isEmpty
            } map translateMember(vc)

            Completion(members, errors)
          })

        } else Completion(Nil, errors)

      })

      val deleteResponse = new Response[Unit]
      askFilesDeleted(List(sourceFile), deleteResponse)
      getOrThrow(deleteResponse)

      result
    }
  }

  def getCompleter[C <: ExpressionContext[_, _] : TypeTag, T: TypeTag](
    profile: ExpressionProfile,
    template: Boolean = true,
    setter: Boolean = false,
    header: String = ""): Completer = {

    import scala.reflect.runtime.universe._

    val mirror = typeTag[C].mirror
    val contextType = typeOf[C]
    val resultType = typeOf[T]
    val TypeRef(_, _, List(rootObjectType, _)) = contextType.baseType(typeOf[ExpressionContext[_, _]].typeSymbol)
    val rootObjectClass = mirror.runtimeClass(rootObjectType)

    getCompleter(profile, template, setter, header, contextType.toString, rootObjectClass, resultType.toString)
  }

  protected def getCompleter(
    profile: ExpressionProfile,
    template: Boolean,
    setter: Boolean,
    header: String,
    contextType: String,
    rootObjectClass: Class[_],
    resultType: String): Completer = {

    new CompleterImpl(profile, template, setter, header, contextType, rootObjectClass, resultType)
  }

  override protected def compile(sourceFile: SourceFile, classLoader: ScexClassLoader, usedInExpressions: Boolean) = {
    val result = super.compile(sourceFile, classLoader, usedInExpressions)

    if (result.isEmpty && usedInExpressions) {
      lock.synchronized {
        val global = this.global
        val response = new global.Response[global.Tree]
        global.askLoadedTyped(sourceFile, response)
        getOrThrow(response)
      }
    }

    result
  }

  override def reset() {
    lock.synchronized {
      synchronized {
        super.reset()
        typeCompletionCache.invalidateAll()
        init()
      }
    }
  }
}

object ScexPresentationCompiler {

  case class Param(name: String, tpe: String)

  case class Member(name: String, params: List[List[Param]], tpe: String, iimplicit: Boolean)

  case class Completion(members: List[Member], errors: List[CompileError])

}