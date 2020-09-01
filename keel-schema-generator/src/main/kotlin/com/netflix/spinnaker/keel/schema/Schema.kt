package com.netflix.spinnaker.keel.schema

data class RootSchema(
  val `$id`: String,
  val description: String,
  val properties: Map<String, Property>,
  val required: List<String>,
  val `$defs`: Map<String, Schema>
) {
  val `$schema`: String = "http://json-schema.org/draft-07/schema#"
  val type: String = "object"
}

data class Schema(
  val properties: Map<String, Property>,
  val required: List<String>
) {
  val type: String = "object"
}

interface Property

data class OneOf(
  val oneOf: List<Property>
) : Property

sealed class TypedProperty(
  val type: String
) : Property

object NullProperty : TypedProperty("null")

object BooleanProperty : TypedProperty("boolean")

object IntegerProperty : TypedProperty("integer")

data class StringProperty(
  val format: String? = null
) : TypedProperty("string")

data class EnumProperty(
  val enum: List<String>
) : Property

data class Ref(
  val `$ref`: String
) : Property
