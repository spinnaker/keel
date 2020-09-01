package com.netflix.spinnaker.keel.schema

import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.javaType

class Generator {

  fun <TYPE : Any> generateSchema(type: KClass<TYPE>): RootSchema =
    RootSchema(
      `$id` = "http://keel.spinnaker.io/delivery-config",
      description = "The schema for delivery configs in Keel",
      properties = type.memberProperties.associate {
        it.name to buildProperty(it)
      },
      required = type.memberProperties.filter { property ->
        !type.findConstructorParamFor(property).isOptional
      }
        .map { it.name },
      definitions = emptyMap()
    )

  private fun <TYPE : Any> KClass<TYPE>.findConstructorParamFor(property: KProperty1<TYPE, *>) =
    primaryConstructor
      ?.parameters
      ?.find { it.name == property.name }
      ?: TODO("handle property with no constructor param")

  private fun buildProperty(property: KProperty1<*, *>): PropertyOrOneOf {
    val javaType = property.returnType.javaType
    return when {
      property.returnType.isMarkedNullable -> OneOf(
        listOf(NullProperty, buildProperty(property.returnType.withNullability(false).javaType))
      )
      else -> buildProperty(javaType)
    }
  }

  private fun buildProperty(type: Type): PropertyOrOneOf {
    return when {
      type.isEnum -> EnumProperty(type.enumNames)
      type == String::class.java -> StringProperty()
      type == Boolean::class.java -> BooleanProperty
      type == Int::class.java -> IntegerProperty
      else -> TODO()
    }
  }

  @Suppress("UNCHECKED_CAST")
  private val Type.enumNames: List<String>
    get() = (this as? Class<Enum<*>>)?.enumConstants?.map { it.name } ?: emptyList()

  private val Type.isEnum: Boolean
    get() = (this as? Class<*>)?.isEnum ?: false
}

inline fun <reified TYPE : Any> Generator.generateSchema() = generateSchema(TYPE::class)
