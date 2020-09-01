package com.netflix.spinnaker.keel.schema

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.containsKey
import strikt.assertions.doesNotContain
import strikt.assertions.get
import strikt.assertions.isA

internal class GeneratorTests {

  @Nested
  @DisplayName("a simple data class")
  class SimpleDataClass {
    data class Foo(val str: String?)

    val schema = generateSchema<Foo>()

    @Test
    fun `documents all properties`() {
      expectThat(schema)
        .get { properties }
        .containsKey(Foo::str.name)
        .get(Foo::str.name)
        .isA<StringProperty>()
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
      expectThat(schema.properties) {
        get(Foo::string.name).isA<StringProperty>()
        get(Foo::boolean.name).isA<BooleanProperty>()
        get(Foo::integer.name).isA<IntegerProperty>()
      }
    }
  }

  @Nested
  @DisplayName("enum properties")
  class EnumProperties {
    data class Foo(
      val size: Size
    )

    enum class Size {
      tall, grande, venti
    }

    val schema = generateSchema<Foo>()

    @Test
    fun `applies correct property types`() {
      expectThat(schema.properties)
        .get(Foo::size.name)
        .isA<EnumProperty>()
        .get { enum }
        .containsExactly(Size.values().map { it.name })
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
    expectThat(schema.required)
      .doesNotContain(Foo::optionalString.name)
  }

  @Test
  fun `properties without defaults are required`() {
    expectThat(schema.required)
      .contains(Foo::requiredString.name)
  }
}

inline fun <reified T : Any> generateSchema() =
  Generator()
    .generateSchema<T>()
    .also {
      jacksonObjectMapper().writeValueAsString(it)
    }
