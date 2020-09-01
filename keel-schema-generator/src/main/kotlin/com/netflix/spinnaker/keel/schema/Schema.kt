package com.netflix.spinnaker.keel.schema

data class RootSchema(
  val `$id`: String,
  val description: String,
  val properties: Map<String, PropertyOrOneOf>,
  val required: List<String>,
  val definitions: Map<String, Schema>
) {
  val `$schema`: String = "http://json-schema.org/draft-07/schema#"
  val type: String = "object"
}

data class Schema(
  val properties: Map<String, PropertyOrOneOf>,
  val required: List<String>
) {
  val type: String = "object"
}

interface PropertyOrOneOf

data class OneOf(
  val oneOf: List<PropertyOrOneOf>
) : PropertyOrOneOf

sealed class Property(
  val type: String
) : PropertyOrOneOf

object NullProperty : Property("null")

object BooleanProperty : Property("boolean")

object IntegerProperty : Property("integer")

data class StringProperty(
  val format: String? = null
) : Property("string")

data class EnumProperty(
  val enum: List<String>
) : PropertyOrOneOf
