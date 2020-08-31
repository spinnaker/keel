package com.netflix.spinnaker.keel.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

class Generator {

  private val schemas = mutableMapOf<String, Map<String, Any>>()

  fun <TYPE : Any> generateSchema(type: KClass<TYPE>): JsonNode {
    val schema = mutableMapOf<String, Any>()
      .also { schemas[type.simpleName!!] = it }

    schema["type"] = "object"
    schema["properties"] = type.memberProperties.associate {
      it.name to propertySchema(it)
    }
    schema["required"] = type.memberProperties.filter { property ->
      !type.findConstructorParamFor(property).isOptional
    }
      .map { it.name }

    return ObjectMapper()
      .valueToTree(
        mapOf(
          "components" to mapOf(
            "schemas" to schemas
          )
        )
      )
  }

  private fun <TYPE : Any> KClass<TYPE>.findConstructorParamFor(property: KProperty1<TYPE, *>) =
    primaryConstructor
      ?.parameters
      ?.find { it.name == property.name }
      ?: TODO("handle property with no constructor param")

  private fun propertySchema(it: KProperty1<*, *>): Map<String, Any> {
    val schema = mutableMapOf<String, Any>()
    schema += propertyType(it)
    return schema
  }

  private fun propertyType(it: KProperty1<*, *>): Map<String, Any> {
    val javaType = it.returnType.javaType
    return when {
      javaType.isEnum -> mapOf("type" to "string", "enum" to javaType.enumNames)
      javaType == String::class.java -> mapOf("type" to "string")
      javaType == Boolean::class.java -> mapOf("type" to "boolean")
      javaType == Int::class.java -> mapOf("type" to "integer")
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
