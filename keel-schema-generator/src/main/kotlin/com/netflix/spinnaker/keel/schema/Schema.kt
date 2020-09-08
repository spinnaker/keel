package com.netflix.spinnaker.keel.schema

import java.util.SortedMap
import java.util.SortedSet

interface Schema

sealed class TypedProperty(
  val type: String
) : Schema

data class RootSchema(
  val `$id`: String,
  val title: String?,
  val description: String,
  val properties: Map<String, Schema>,
  val required: SortedSet<String>,
  val discriminator: OneOf.Discriminator? = null,
  val `$defs`: SortedMap<String, Schema>
) {
  val `$schema`: String = "https://json-schema.org/draft/2019-09/schema"
  val type: String = "object"
}

data class ObjectSchema(
  val title: String?,
  val properties: Map<String, Schema>,
  val required: SortedSet<String>,
  val discriminator: OneOf.Discriminator? = null
) : TypedProperty("object")

object NullSchema : TypedProperty("null")

object BooleanSchema : TypedProperty("boolean")

object IntegerSchema : TypedProperty("integer")

object NumberSchema : TypedProperty("number")

object AnySchema : TypedProperty("object") {
  @Suppress("MayBeConstant") // TODO: doesn't serialize if declared as const
  val additionalProperties: Boolean = true
}

data class ArraySchema(
  val items: Schema,
  val uniqueItems: Boolean? = null,
  val minItems: Int? = null
) : TypedProperty("array")

data class MapSchema(
  val additionalProperties: Schema
) : TypedProperty("object")

data class StringSchema(
  val format: String? = null
) : TypedProperty("string")

data class EnumSchema(
  val enum: List<String>
) : Schema

data class Reference(
  val `$ref`: String
) : Schema

data class OneOf(
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
) : Schema
