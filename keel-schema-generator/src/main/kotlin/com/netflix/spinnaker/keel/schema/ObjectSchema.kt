package com.netflix.spinnaker.keel.schema

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

interface Schema

sealed class TypedProperty(
  val type: String
) : Schema

data class ObjectSchema(
  val properties: Map<String, Schema>,
  val required: List<String>
) : TypedProperty("object")

data class OneOf(
  val oneOf: List<Schema>
) : Schema

object NullSchema : TypedProperty("null")

object BooleanSchema : TypedProperty("boolean")

object IntegerSchema : TypedProperty("integer")

data class StringSchema(
  val format: String? = null
) : TypedProperty("string")

data class EnumSchema(
  val enum: List<String>
) : Schema

data class Ref(
  val `$ref`: String
) : Schema
