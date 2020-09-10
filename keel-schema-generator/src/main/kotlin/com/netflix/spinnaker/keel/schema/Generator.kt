package com.netflix.spinnaker.keel.schema

import com.fasterxml.jackson.annotation.JsonCreator
import com.netflix.spinnaker.keel.api.schema.Discriminator
import com.netflix.spinnaker.keel.api.schema.Factory
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection.Companion.invariant
import kotlin.reflect.full.createType
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure

class Generator(
  private val extensionRegistry: ExtensionRegistry,
  private val options: Options = Options()
) {

  data class Options(
    val nullableAsOneOf: Boolean = false
  )

  /**
   * Contains linked schemas that we find along the way.
   */
  private data class Context(
    val definitions: MutableMap<String, Schema> = mutableMapOf()
  )

  /**
   * Generate a schema for [type].
   *
   * @return a full schema document including `$defs` of all linked schemas required to fully
   * specify [type].
   */
  fun <TYPE : Any> generateSchema(type: KClass<TYPE>): RootSchema {
    val context = Context()

    val schema = context.buildSchema(type) as ObjectSchema

    return RootSchema(
      `$id` = "http://keel.spinnaker.io/${type.simpleName}",
      title = checkNotNull(type.simpleName),
      description = "The schema for delivery configs in Keel",
      properties = schema.properties,
      required = schema.required,
      discriminator = schema.discriminator,
      `$defs` = context.definitions.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    )
  }

  /**
   * Build a schema for [type]. Any referenced schemas not already defined will be added to
   * the [Context] as they are discovered.
   */
  private fun Context.buildSchema(type: KClass<*>): Schema =
    when {
      type.isSealed ->
        OneOf(
          oneOf = type.sealedSubclasses.map { define(it) }.toSet()
        )
      extensionRegistry.baseTypes().contains(type.java) -> {
        OneOf(
          oneOf = extensionRegistry.extensionsOf(type.java).map { define(it.value.kotlin) }.toSet(),
          discriminator = OneOf.Discriminator(
            propertyName = type.discriminatorPropertyName,
            mapping = extensionRegistry
              .extensionsOf(type.java)
              .mapValues { it.value.kotlin.buildRef().`$ref` }
              .toSortedMap(String.CASE_INSENSITIVE_ORDER)
          )
        )
      }
      type.typeParameters.isNotEmpty() -> {
        val invariantTypes = extensionRegistry.extensionsOf(type.typeParameters.first().upperBounds.first().jvmErasure.java)
        ObjectSchema(
          title = checkNotNull(type.simpleName),
          properties = type
            .candidateProperties
            .filter {
              // filter out properties of the generic type as they will be specified in extended types
              it.type.classifier !in type.typeParameters
            }
            .associate {
              checkNotNull(it.name) to buildProperty(it.type)
            },
          required = type
            .candidateProperties
            .filter { !it.isOptional }
            .map { checkNotNull(it.name) }
            .toSortedSet(String.CASE_INSENSITIVE_ORDER),
          discriminator = OneOf.Discriminator(
            propertyName = type.discriminatorPropertyName,
            mapping = invariantTypes
              .mapValues { it.value.kotlin.buildRef().`$ref` }
              .toSortedMap(String.CASE_INSENSITIVE_ORDER)
          )
        )
          .also {
            invariantTypes
              .forEach { (_, subType) ->
                type.createType(
                  arguments = listOf(invariant(subType.kotlin.createType()))
                )
                  .also { _ ->
                    val name = "${subType.simpleName}${type.simpleName}"
                    if (!definitions.containsKey(name)) {
                      val genericProperties = type.candidateProperties.filter {
                        it.type.classifier in type.typeParameters
                      }
                      definitions[name] = AllOf(
                        listOf(
                          Reference("#/${RootSchema::`$defs`.name}/${type.simpleName}"),
                          ObjectSchema(
                            title = null,
                            properties = genericProperties
                              .associate {
                                checkNotNull(it.name) to buildProperty(subType.kotlin.createType())
                              },
                            required = genericProperties
                              .filter { !it.isOptional }
                              .map { checkNotNull(it.name) }
                              .toSortedSet(String.CASE_INSENSITIVE_ORDER)
                          )
                        )
                      )
                    }
                  }
              }
          }
      }
      else ->
        ObjectSchema(
          title = checkNotNull(type.simpleName),
          properties = type.candidateProperties.associate {
            checkNotNull(it.name) to buildProperty(it.type)
          },
          required = type
            .candidateProperties
            .filter { !it.isOptional }
            .filter { options.nullableAsOneOf || !it.type.isMarkedNullable }
            .map { checkNotNull(it.name) }
            .toSortedSet(String.CASE_INSENSITIVE_ORDER)
        )
    }

  /**
   * The properties of as type that we will want to document in it's schema. Unless otherwise
   * specified this means the parameters of its primary constructor.
   */
  private val KClass<*>.candidateProperties: List<KParameter>
    get() = when {
      isAbstract -> emptyList()
      isObject -> emptyList()
      else -> preferredConstructor.parameters
    }

  private val KClass<*>.preferredConstructor: KFunction<Any>
    get() = (
      constructors.firstOrNull { it.hasAnnotation<Factory>() }
        ?: constructors.firstOrNull { it.hasAnnotation<JsonCreator>() }
        ?: primaryConstructor
        ?: constructors.firstOrNull()
      ).let {
        checkNotNull(it) {
          "$qualifiedName has no candidate constructor"
        }
      }

  /**
   * The name of the property annotated with `@[Discriminator]`
   */
  private val KClass<*>.discriminatorPropertyName: String
    get() = checkNotNull(memberProperties.find { it.hasAnnotation<Discriminator>() }) {
      "$simpleName has no property annotated with @Discriminator but is registered as an extension base type"
    }
      .name

  /**
   * Build the property schema for [type].
   *
   * - In the case of a nullable property, this is [OneOf] `null` and the non-null type.
   * - In the case of a string, integer, boolean, or enum this is a [TypedProperty].
   * - In the case of an array-like type this is an [ArraySchema].
   * - In the case of a [Map] this is a [MapSchema].
   * - Otherwise this is is a [Reference] to the schema for the type, which will be added to this
   * [Context] if not already defined.r
   */
  private fun Context.buildProperty(type: KType): Schema =
    when {
      type.isMarkedNullable -> if (options.nullableAsOneOf) {
        OneOf(
          setOf(NullSchema, buildProperty(type.withNullability(false)))
        )
      } else {
        buildProperty(type.withNullability(false))
      }
      type.isEnum -> EnumSchema(type.enumNames)
      type.isString -> StringSchema(format = type.stringFormat)
      type.isBoolean -> BooleanSchema
      type.isInteger -> IntegerSchema
      type.isNumber -> NumberSchema
      type.isArray -> {
        ArraySchema(
          items = buildProperty(type.elementType),
          uniqueItems = if (type.isUniqueItems) true else null
        )
      }
      type.isMap -> {
        MapSchema(
          additionalProperties = buildProperty(type.valueType)
        )
      }
      type.jvmErasure == Any::class -> AnySchema
      else -> define(type)
    }

  /**
   * If a schema for [type] is not yet defined, define it now.
   *
   * @return a [Reference] to the schema for [type].
   */
  private fun Context.define(type: KType): Reference =
    define(type.jvmErasure)

  /**
   * If a schema for [type] is not yet defined, define it now.
   *
   * @return a [Reference] to the schema for [type].
   */
  private fun Context.define(type: KClass<*>): Reference {
    val name = checkNotNull(type.simpleName)
    if (!definitions.containsKey(name)) {
      definitions[name] = buildSchema(type)
    }
    return type.buildRef()
  }

  /**
   * Build a `$ref` URL to the schema for this type.
   */
  private fun KClass<*>.buildRef() =
    Reference("#/${RootSchema::`$defs`.name}/${simpleName}")

  /**
   * Is this something we should represent as an enum?
   */
  private val KType.isEnum: Boolean
    get() = jvmErasure.java.isEnum

  /**
   * Is this something we should represent as a string?
   */
  private val KType.isString: Boolean
    get() = jvmErasure == String::class || jvmErasure in formattedTypes.keys

  /**
   * Is this something we should represent as a boolean?
   */
  private val KType.isBoolean: Boolean
    get() = jvmErasure == Boolean::class

  /**
   * Is this something we should represent as an integer?
   */
  private val KType.isInteger: Boolean
    get() = jvmErasure == Int::class || jvmErasure == Short::class || jvmErasure == Long::class

  /**
   * Is this something we should represent as a number?
   */
  private val KType.isNumber: Boolean
    get() = jvmErasure == Float::class || jvmErasure == Double::class

  /**
   * Is this something we should represent as an array?
   */
  private val KType.isArray: Boolean
    get() = jvmErasure.isSubclassOf(Collection::class) || (javaType as? Class<*>)?.isArray ?: false

  /**
   * Is this something we should represent as a key-value hash?
   */
  private val KType.isMap: Boolean
    get() = jvmErasure.isSubclassOf(Map::class)

  /**
   * Is this an array-like type with unique values?
   */
  private val KType.isUniqueItems: Boolean
    get() = jvmErasure.isSubclassOf(Set::class)

  /**
   * The names of all the values of an enum as they should appear in the schema.
   */
  @Suppress("UNCHECKED_CAST")
  private val KType.enumNames: List<String>
    get() {
      require(isEnum) {
        "enumNames is only valid on enum types"
      }
      return (jvmErasure.java as Class<Enum<*>>).enumConstants.map { it.name }
    }

  /**
   * The element type for a [Collection].
   */
  private val KType.elementType: KType
    get() {
      require(jvmErasure.isSubclassOf(Collection::class) || jvmErasure.java.isArray) {
        "elementType is only valid on Collections"
      }
      return checkNotNull(arguments.first().type) { "unhandled generic type: ${arguments.first()}" }
    }

  /**
   * The value type for a [Collection].
   */
  private val KType.valueType: KType
    get() {
      require(jvmErasure.isSubclassOf(Map::class)) {
        "valueType is only valid on Maps"
      }
      return checkNotNull(arguments[1].type) { "unhandled generic type: ${arguments[1]}" }
    }

  /**
   * Is this class a singleton object?
   */
  private val KClass<*>.isObject: Boolean
    get() = objectInstance != null

  private val formattedTypes = mapOf(
    Duration::class to "duration",
    Instant::class to "date-time",
    ZonedDateTime::class to "date-time",
    OffsetDateTime::class to "date-time",
    LocalDateTime::class to "date-time",
    LocalDate::class to "date",
    LocalTime::class to "time"
  )

  /**
   * The `format` for a string schema, if any.
   */
  private val KType.stringFormat: String?
    get() = formattedTypes[jvmErasure]
}

inline fun <reified TYPE : Any> Generator.generateSchema() = generateSchema(TYPE::class)
