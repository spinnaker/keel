package com.netflix.spinnaker.keel.schema

interface Schema

sealed class TypedProperty(
  val type: String
) : Schema

data class RootSchema(
  val `$id`: String,
  val description: String,
  val properties: Map<String, Schema>,
  val required: List<String>,
  val `$defs`: Map<String, Schema>
) {
  val `$schema`: String = "http://json-schema.org/draft-07/schema#"
  val type: String = "object"
}

data class ObjectSchema(
  val properties: Map<String, Schema>,
  val required: List<String>
) : TypedProperty("object")

object NullSchema : TypedProperty("null")

object BooleanSchema : TypedProperty("boolean")

object IntegerSchema : TypedProperty("integer")

data class ArraySchema(
  val items: Schema,
  val uniqueItems: Boolean? = null,
  val minItems: Int? = null
) : TypedProperty("array")

data class StringSchema(
  val format: String? = null
) : TypedProperty("string")

data class EnumSchema(
  val enum: List<String>
) : Schema

data class Ref(
  val `$ref`: String
) : Schema

data class OneOf(
  val oneOf: List<Schema>
) : Schema
