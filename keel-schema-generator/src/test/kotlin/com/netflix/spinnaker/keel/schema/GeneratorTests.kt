package com.netflix.spinnaker.keel.schema

import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
import com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT
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
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.one

internal class GeneratorTests {

  @Nested
  @DisplayName("a simple data class")
  class SimpleDataClass {
    data class Foo(val str: String)

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

  @Nested
  @DisplayName("nullable properties")
  class NullableProperties {
    data class Foo(
      val nullableString: String?
    )

    val schema = generateSchema<Foo>()

    @Test
    fun `nullable properties are defined as one-of null or the regular type`() {
      expectThat(schema.properties[Foo::nullableString.name])
        .isA<OneOf>()
        .get { oneOf }
        .hasSize(2)
        .one { isA<NullProperty>() }
        .one { isA<StringProperty>() }
    }
  }

  @Nested
  @DisplayName("complex properties")
  class ComplexProperties {
    data class Foo(
      val bar: Bar
    )

    data class Bar(
      val str: String
    )

    val schema = generateSchema<Foo>()

    @Test
    fun `complex property is defined as a $ref`() {
      expectThat(schema.properties[Foo::bar.name])
        .isA<Ref>()
        .get { `$ref` }
        .isEqualTo("#/definitions/${Bar::class.java.simpleName}")
    }

    @Test
    fun `referenced schema is included in $defs`() {
      expectThat(schema.`$defs`)
        .hasSize(1)
        .get(Bar::class.java.simpleName)
        .isA<Schema>()
        .get { properties }
        .get(Bar::str.name)
        .isA<StringProperty>()
    }
  }

  @Nested
  @DisplayName("nested complex properties")
  class NestedComplexProperties {
    data class Foo(
      val bar: Bar
    )

    data class Bar(
      val baz: Baz
    )

    data class Baz(
      val str: String
    )

    val schema = generateSchema<Foo>()

    @Test
    fun `nested complex property is defined as a $ref`() {
      expectThat(schema.`$defs`)
        .get(Bar::class.java.simpleName)
        .isA<Schema>()
        .get { properties }
        .get(Bar::baz.name)
        .isA<Ref>()
        .get { `$ref` }
        .isEqualTo("#/definitions/${Baz::class.java.simpleName}")
    }

    @Test
    fun `deep referenced schema is included in $defs`() {
      expectThat(schema.`$defs`)
        .hasSize(2)
        .containsKey(Bar::class.java.simpleName)
        .containsKey(Baz::class.java.simpleName)
        .get(Baz::class.java.simpleName)
        .isA<Schema>()
        .get { properties }
        .get(Baz::str.name)
        .isA<StringProperty>()
    }
  }
}

inline fun <reified T : Any> generateSchema() =
  Generator()
    .generateSchema<T>()
    .also {
      jacksonObjectMapper()
        .setSerializationInclusion(NON_NULL)
        .enable(INDENT_OUTPUT)
        .writeValueAsString(it).also(::println)
    }
