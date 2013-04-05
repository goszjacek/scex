package com.avsystem.scex.validation

import java.{util => ju, lang => jl}
import scala.reflect.macros.Context
import scala.language.experimental.macros
import com.avsystem.scex.utils.MacroUtils._

object Validator {
  var accessValidator: AccessValidator = null
  var syntaxValidator: SyntaxValidator = null

  def validate[T >: Null](expr: T): T = macro validate_impl[T]

  def validate_impl[T >: Null](c: Context)(expr: c.Expr[T]): c.Expr[T] = {
    import c.universe._

    expr.tree.foreach { subtree =>
      if (!syntaxValidator.isSyntaxAllowed(c.universe)(subtree)) {
        c.error(subtree.pos, s"Forbidden construct: ${subtree.getClass.getSimpleName}")
      }
    }

    def tpeOrNullIfJavaModule(qualifier: Tree) = {
      val qualifierSymbol = qualifier.symbol
      if (qualifierSymbol != null && qualifierSymbol.isJava && isModuleOrPackage(qualifierSymbol))
        null
      else
        qualifier.tpe
    }

    def needsValidation(symbol: Symbol) =
      symbol != null && (symbol.isMethod || isJavaField(symbol))

    def validateAccess(pos: Position, tpe: Type, symbol: Symbol, icSymbol: Symbol) {
      if (needsValidation(symbol)) {
        if (!accessValidator.isInvocationAllowed(c.universe)(tpe, symbol, icSymbol).getOrElse(false)) {
          c.error(pos, s"Member ${symbol.fullName} is not allowed on $tpe")
        }
      }
    }

    def validateTree(tree: Tree) {
      tree match {
        case tree@Select(apply@Apply(fun, List(qualifier)), _) if isStaticImplicitConversion(fun.symbol) && apply.pos == qualifier.pos =>
          //unwrap qualifier from implicit conversion
          validateAccess(tree.pos, tpeOrNullIfJavaModule(qualifier), tree.symbol, fun.symbol)
          validateTree(qualifier)
        case tree@Select(qualifier, _) =>
          validateAccess(tree.pos, tpeOrNullIfJavaModule(qualifier), tree.symbol, null)
          validateTree(qualifier)
        case _ =>
          tree.children.foreach(child => validateTree(child))
      }
    }

    validateTree(expr.tree)

    expr
  }
}
