package com.netflix.spinnaker.keel.schema

data class RootSchema(
  val `$id`: String,
  val description: String,
  val properties: Map<String, Node>,
  val required: List<String>,
  val `$defs`: Map<String, Node>
) {
  val `$schema`: String = "http://json-schema.org/draft-07/schema#"
  val type: String = "object"
}

interface Node

data class Schema(
  val properties: Map<String, Node>,
  val required: List<String>
) : Node {
  val type: String = "object"
}

interface Property : Node

data class OneOf(
  val oneOf: List<Node>
) : Node

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
) : Node
