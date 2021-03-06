package com.avsystem.scex
package japi

import java.lang.reflect.Type
import java.{util => ju}

import com.avsystem.scex.compiler.JavaTypeParsing._
import com.avsystem.scex.compiler.presentation.ScexPresentationCompiler
import com.avsystem.scex.compiler.{ExpressionDef, ScexCompiler}
import com.avsystem.scex.util.Fluent
import com.google.common.cache.CacheBuilder
import com.google.common.reflect.TypeToken

import com.avsystem.commons.jiop.JavaInterop._

trait JavaScexCompiler extends ScexCompiler with ScexPresentationCompiler {

  import com.avsystem.scex.util.CacheImplicits._

  private val typesCache = CacheBuilder.newBuilder.weakKeys
    .build[Type, String](javaTypeAsScalaType _)

  private val rootObjectClassCache = CacheBuilder.newBuilder.weakKeys
    .build[TypeToken[_ <: ExpressionContext[_, _]], Class[_]](getRootObjectClass _)

  private def getRootObjectClass(token: TypeToken[_ <: ExpressionContext[_, _]]): Class[_] =
    token.getSupertype(classOf[ExpressionContext[_, _]]).getType match {
      case ParameterizedType(_, _, Array(rootObjectType, _)) => rootObjectType match {
        case TypeAny | TypeAnyVal => null
        case _ => TypeToken.of(rootObjectType).getRawType
      }
      case clazz if clazz == classOf[ExpressionContext[_, _]] => classOf[Object]
    }

  def buildExpression: ExpressionBuilder[_ <: ExpressionContext[_, _], _] =
    new ExpressionBuilder[ExpressionContext[_, _], Any]

  class ExpressionBuilder[C <: ExpressionContext[_, _], T] extends Fluent {
    private var _contextTypeToken: TypeToken[_ <: ExpressionContext[_, _]] = _
    private var _resultTypeToken: TypeToken[_] = _
    private var _profile: ExpressionProfile = _
    private var _expression: String = _
    private var _template: Boolean = true
    private var _setter: Boolean = false
    private var _header: String = ""
    private val _variableTypes: ju.Map[String, TypeToken[_]] = new ju.HashMap

    def get: Expression[C, T] = {
      require(_contextTypeToken != null, "Context type cannot be null")
      require(_resultTypeToken != null, "Result type cannot be null")
      require(_profile != null, "Profile cannot be null")
      require(_expression != null, "Expression cannot be null")
      require(_header != null, "Header cannot be null")

      val scalaContextType = typesCache.get(_contextTypeToken.getType)
      val scalaResultType = typesCache.get(_resultTypeToken.getType)
      val rootObjectClass = rootObjectClassCache.get(_contextTypeToken)
      val variableTypes = _variableTypes.asScala.iterator.map {
        case (key, value) => (key, typesCache.get(value.getType))
      }.toMap

      val (actualExpression, positionMapping) = preprocess(_expression, _template)
      getCompiledExpression[C, T](ExpressionDef(_profile, _template, _setter, actualExpression, _header,
        scalaContextType, scalaResultType, variableTypes)(_expression, positionMapping, rootObjectClass))
    }

    def contextType[NC <: ExpressionContext[_, _]](contextTypeToken: TypeToken[NC]): ExpressionBuilder[NC, T] = fluent {
      _contextTypeToken = contextTypeToken
    }.asInstanceOf[ExpressionBuilder[NC, T]]

    def contextType[NC <: ExpressionContext[_, _]](contextClass: Class[NC]): ExpressionBuilder[NC, T] = fluent {
      _contextTypeToken = TypeToken.of(contextClass)
    }.asInstanceOf[ExpressionBuilder[NC, T]]

    def resultType[NT](resultTypeToken: TypeToken[NT]): ExpressionBuilder[C, NT] = fluent {
      _resultTypeToken = resultTypeToken
      _setter = false
    }.asInstanceOf[ExpressionBuilder[C, NT]]

    def resultType[NT](resultClass: Class[NT]): ExpressionBuilder[C, NT] = fluent {
      _resultTypeToken = TypeToken.of(resultClass)
      _setter = false
    }.asInstanceOf[ExpressionBuilder[C, NT]]

    def setterFor[NT](resultTypeToken: TypeToken[NT]): ExpressionBuilder[C, Setter[NT]] = fluent {
      _resultTypeToken = resultTypeToken
      _setter = true
    }.asInstanceOf[ExpressionBuilder[C, Setter[NT]]]

    def setterFor[NT](resultClass: Class[NT]): ExpressionBuilder[C, Setter[NT]] = fluent {
      _resultTypeToken = TypeToken.of(resultClass)
      _setter = true
    }.asInstanceOf[ExpressionBuilder[C, Setter[NT]]]

    def profile(profile: ExpressionProfile): this.type = fluent {
      _profile = profile
    }

    def expression(expression: String): this.type = fluent {
      _expression = expression
    }

    def template(template: Boolean): this.type = fluent {
      _template = template
    }

    def additionalHeader(header: String): this.type = fluent {
      _header = header
    }

    def variableTypes(variableTypes: ju.Map[String, TypeToken[_]]): this.type = fluent {
      _variableTypes.clear()
      _variableTypes.putAll(variableTypes)
    }

    def variableType(name: String, typeToken: TypeToken[_]): this.type = fluent {
      _variableTypes.put(name, typeToken)
    }

    def variableClasses(variableTypes: ju.Map[String, Class[_]]): this.type = fluent {
      _variableTypes.clear()
      variableTypes.entrySet.iterator.asScala.foreach { e =>
        _variableTypes.put(e.getKey, TypeToken.of(e.getValue))
      }
    }

    def variableClass(name: String, clazz: Class[_]): this.type = fluent {
      _variableTypes.put(name, TypeToken.of(clazz))
    }
  }

  def buildCompleter =
    new CompleterBuilder

  class CompleterBuilder extends Fluent {
    private var _contextTypeToken: TypeToken[_ <: ExpressionContext[_, _]] = _
    private var _resultTypeToken: TypeToken[_] = _
    private var _profile: ExpressionProfile = _
    private var _template: Boolean = true
    private var _setter: Boolean = false
    private var _header: String = ""
    private val _variableTypes: ju.Map[String, TypeToken[_]] = new ju.HashMap

    def get: Completer = {
      require(_contextTypeToken != null, "Context type cannot be null")
      require(_resultTypeToken != null, "Result type cannot be null")
      require(_profile != null, "Profile cannot be null")
      require(_header != null, "Header cannot be null")

      val scalaContextType = typesCache.get(_contextTypeToken.getType)
      val scalaResultType = typesCache.get(_resultTypeToken.getType)
      val rootObjectClass = rootObjectClassCache.get(_contextTypeToken)
      val variableTypes = _variableTypes.asScala.iterator.map {
        case (key, value) => (key, typesCache.get(value.getType))
      }.toMap

      getCompleter(_profile, _template, _setter, _header, scalaContextType, rootObjectClass, scalaResultType, variableTypes)
    }

    def contextType(contextTypeToken: TypeToken[_ <: ExpressionContext[_, _]]): this.type = fluent {
      _contextTypeToken = contextTypeToken
    }

    def contextType(contextClass: Class[_ <: ExpressionContext[_, _]]): this.type = fluent {
      _contextTypeToken = TypeToken.of(contextClass)
    }

    def resultType(resultTypeToken: TypeToken[_]): this.type = fluent {
      _resultTypeToken = resultTypeToken
      _setter = false
    }

    def resultType(resultClass: Class[_]): this.type = fluent {
      _resultTypeToken = TypeToken.of(resultClass)
      _setter = false
    }

    def setterForType(resultTypeToken: TypeToken[_]): this.type = fluent {
      _resultTypeToken = _resultTypeToken
      _setter = true
    }

    def setterForType(resultClass: Class[_]): this.type = fluent {
      _resultTypeToken = TypeToken.of(resultClass)
      _setter = true
    }

    def profile(profile: ExpressionProfile): this.type = fluent {
      _profile = profile
    }

    def template(template: Boolean): this.type = fluent {
      _template = template
    }

    def additionalHeader(header: String): this.type = fluent {
      _header = header
    }

    def variableTypes(variableTypes: ju.Map[String, TypeToken[_]]): this.type = fluent {
      _variableTypes.clear()
      _variableTypes.putAll(variableTypes)
    }

    def variableType(name: String, typeToken: TypeToken[_]): this.type = fluent {
      _variableTypes.put(name, typeToken)
    }

    def variableClasses(variableTypes: ju.Map[String, Class[_]]): this.type = fluent {
      _variableTypes.clear()
      variableTypes.entrySet.iterator.asScala.foreach { e =>
        _variableTypes.put(e.getKey, TypeToken.of(e.getValue))
      }
    }

    def variableClass(name: String, clazz: Class[_]): this.type = fluent {
      _variableTypes.put(name, TypeToken.of(clazz))
    }
  }

}
