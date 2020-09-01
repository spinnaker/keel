package com.netflix.spinnaker.keel.schema

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure

class Generator {

  private data class Context(
    val definitions: MutableMap<String, Schema> = mutableMapOf()
  )

  fun <TYPE : Any> generateSchema(type: KClass<TYPE>): RootSchema {
    val context = Context()

    val schema = context.buildSchema(type) as ObjectSchema

    return RootSchema(
      `$id` = "http://keel.spinnaker.io/${type.simpleName}",
      description = "The schema for delivery configs in Keel",
      properties = schema.properties,
      required = schema.required,
      `$defs` = context.definitions.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    )
  }

  private fun <TYPE : Any> Context.buildSchema(type: KClass<TYPE>): Schema =
    if (type.isSealed) {
      OneOf(
        oneOf = type.sealedSubclasses.map {
          definitions[it.simpleName!!] = buildSchema(it)
          Ref("#/definitions/${it.simpleName}")
        }
      )
    } else {
      ObjectSchema(
        properties = type.candidateProperties.associate {
          it.name to buildProperty(it)
        },
        required = type.candidateProperties.filter { property ->
          !type.findConstructorParamFor(property).isOptional
        }
          .map { it.name }
      )
    }

  private val <TYPE : Any> KClass<TYPE>.candidateProperties: Collection<KProperty1<TYPE, *>>
    get() = memberProperties.filter { !it.isAbstract }

  private fun <TYPE : Any> KClass<TYPE>.findConstructorParamFor(property: KProperty1<TYPE, *>) =
    primaryConstructor
      ?.parameters
      ?.find { it.name == property.name }
      ?: error("property with no equivalent constructor param")

  private fun Context.buildProperty(property: KProperty1<*, *>): Schema =
    when {
      property.returnType.isMarkedNullable -> OneOf(
        listOf(NullSchema, buildProperty(property.returnType.withNullability(false)))
      )
      else -> buildProperty(property.returnType)
    }

  private fun Context.buildProperty(type: KType): Schema =
    when {
      type.isEnum -> EnumSchema(type.enumNames)
      type.isString -> StringSchema()
      type.isBoolean -> BooleanSchema
      type.isInteger -> IntegerSchema
      type.isArray -> {
        ArraySchema(
          items = buildProperty(type.elementType),
          uniqueItems = if (type.isUniqueItems) true else null
        )
      }
      else -> {
        val javaClass = type.javaType as? Class<*> ?: error("unhandled type: $type")
        definitions[javaClass.simpleName] = buildSchema(javaClass.kotlin)
        Ref("#/definitions/${javaClass.simpleName}")
      }
    }

  @Suppress("UNCHECKED_CAST")
  private val KType.enumNames: List<String>
    get() = (javaType as? Class<Enum<*>>)?.enumConstants?.map { it.name } ?: emptyList()

  private val KType.isEnum: Boolean
    get() = (javaType as? Class<*>)?.isEnum ?: false

  private val KType.isString: Boolean
    get() = javaType == String::class.java

  private val KType.isBoolean: Boolean
    get() = javaType == Boolean::class.java

  private val KType.isInteger: Boolean
    get() = javaType == Int::class.java

  private val KType.isArray: Boolean
    get() = jvmErasure.isSubclassOf(Collection::class)

  private val KType.isUniqueItems: Boolean
    get() = jvmErasure.isSubclassOf(Set::class)

  private val KType.elementType: KType
    get() = checkNotNull(arguments.first().type) { "unhandled generic type: ${arguments.first()}" }
}

inline fun <reified TYPE : Any> Generator.generateSchema() = generateSchema(TYPE::class)
