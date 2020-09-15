package com.netflix.spinnaker.keel.schema

import com.fasterxml.jackson.annotation.JsonValue
import java.util.SortedMap
import java.util.SortedSet

interface Schema {
  val description: String?
}

sealed class TypedProperty(
  val type: String
) : Schema

data class RootSchema(
  val `$id`: String,
  val title: String?,
  val description: String?,
  val properties: Map<String, Schema>,
  val required: SortedSet<String>,
  val discriminator: OneOf.Discriminator? = null,
  val `$defs`: SortedMap<String, Schema>
) {
  @Suppress("unused", "PropertyName")
  val `$schema`: String = "https://json-schema.org/draft/2019-09/schema"
  val type: String = "object"
}

data class ObjectSchema(
  val title: String?,
  override val description: String?,
  val properties: Map<String, Schema>,
  val required: SortedSet<String>,
  val discriminator: OneOf.Discriminator? = null
) : TypedProperty("object")

object NullSchema : TypedProperty("null") {
  override val description: String? = null
}

data class BooleanSchema(override val description: String?) : TypedProperty("boolean")

data class IntegerSchema(override val description: String?) : TypedProperty("integer")

data class NumberSchema(override val description: String?) : TypedProperty("number")

data class AnySchema(override val description: String?) : TypedProperty("object") {
  @Suppress("MayBeConstant") // TODO: doesn't serialize if declared as const
  val additionalProperties: Boolean = true
}

data class ArraySchema(
  override val description: String?,
  val items: Schema,
  val uniqueItems: Boolean? = null,
  val minItems: Int? = null
) : TypedProperty("array")

data class MapSchema(
  override val description: String?,
  val additionalProperties: Either<Schema, Boolean>
) : TypedProperty("object")

data class StringSchema(
  override val description: String?,
  val format: String? = null
) : TypedProperty("string")

data class EnumSchema(
  override val description: String?,
  val enum: List<String>
) : Schema

data class Reference(
  val `$ref`: String
) : Schema {
  override val description: String? = null
}

data class OneOf(
  override val description: String?,
  val oneOf: Set<Schema>,
  val discriminator: Discriminator? = null
) : Schema {
  data class Discriminator(
    val propertyName: String,
    val mapping: SortedMap<String, String>
  )
}

data class AllOf(
  val allOf: List<Schema>
) : Schema {
  override val description: String? = null
}

/**
 * Yes, I really had to implement an either monad to get this all to work.
 */
sealed class Either<L, R> {
  data class Left<L, R>(@JsonValue val value: L) : Either<L, R>()
  data class Right<L, R>(@JsonValue val value: R) : Either<L, R>()
}
