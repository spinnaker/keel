package com.netflix.spinnaker.keel.schema

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import strikt.api.Assertion
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.doesNotContain
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.jackson.at
import strikt.jackson.findValuesAsText
import strikt.jackson.isArray
import strikt.jackson.isObject
import strikt.jackson.path
import strikt.jackson.textValue
import strikt.jackson.textValues

internal class GeneratorTests {

  @Nested
  @DisplayName("a simple data class")
  class SimpleDataClass {
    data class Foo(val str: String?)

    val schema = generateSchema<Foo>()

    @Test
    fun `generates schema for simple data class`() {
      expectThat(schema)
        .at("/components/schemas/${Foo::class.java.simpleName}")
        .isObject()
        .textValueOf("type")
        .isEqualTo("object")
    }

    @Test
    fun `documents all properties`() {
      expectThat(schema)
        .at("/components/schemas/${Foo::class.java.simpleName}/properties")
        .path(Foo::str.name)
        .isObject()
        .path("type")
        .textValue()
        .isEqualTo("string")
    }
  }

  @Nested
  @DisplayName("simple property types")
  class SimplePropertyTypes {
    data class Foo(
      val string: String,
      val integer: Int,
      val boolean: Boolean,
    )

    val schema = generateSchema<Foo>()

    @Test
    fun `applies correct property types`() {
      expectThat(schema.at("/components/schemas/${Foo::class.java.simpleName}/properties")) {
        path(Foo::string.name).path("type").textValue().isEqualTo("string")
        path(Foo::boolean.name).path("type").textValue().isEqualTo("boolean")
        path(Foo::integer.name).path("type").textValue().isEqualTo("integer")
      }
    }
  }

  @Nested
  @DisplayName("properties with default values")
  class OptionalProperties {
    data class Foo(
      val optionalString: String = "default value",
      val requiredString: String
    )

    val schema = generateSchema<Foo>()

    @Test
    fun `properties with defaults are optional`() {
      expectThat(schema.at("/components/schemas/${Foo::class.java.simpleName}/required"))
        .isArray()
        .textValues()
        .doesNotContain(Foo::optionalString.name)
    }

    @Test
    fun `properties without defaults are required`() {
      expectThat(schema.at("/components/schemas/${Foo::class.java.simpleName}/required"))
        .isArray()
        .textValues()
        .contains(Foo::requiredString.name)
    }
  }
}

inline fun <reified T: Any> generateSchema() =
  Generator()
    .generateSchema<T>()
    .also(::println)

fun <T : JsonNode> Assertion.Builder<T>.propertyType(propertyName: String) =
  path(propertyName)
    .isObject()
    .path("type")
    .textValue()

fun <T : JsonNode> Assertion.Builder<T>.textValueOf(fieldName: String) =
  get { findValuesAsText(fieldName).first() }
