@file:Suppress("JUnit5MalformedNestedClass")

package com.netflix.spinnaker.keel.schema

import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
import com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.containsKey
import strikt.assertions.doesNotContain
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import strikt.assertions.isTrue
import strikt.assertions.one
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.reflect.jvm.jvmErasure

internal class GeneratorTests {

  @Nested
  @DisplayName("a simple data class")
  class SimpleDataClass {
    data class Foo(val string: String)

    val schema = generateSchema<Foo>()

    @Test
    fun `documents all properties`() {
      expectThat(schema)
        .get { properties }
        .containsKey(Foo::string.name)
        .get(Foo::string.name)
        .isA<StringSchema>()
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
        get(Foo::string.name).isA<StringSchema>()
        get(Foo::boolean.name).isA<BooleanSchema>()
        get(Foo::integer.name).isA<IntegerSchema>()
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
        .isA<EnumSchema>()
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
        .one { isA<NullSchema>() }
        .one { isA<StringSchema>() }
    }
  }

  @Nested
  @DisplayName("complex properties")
  class ComplexProperties {
    data class Foo(
      val bar: Bar
    )

    data class Bar(
      val string: String
    )

    val schema = generateSchema<Foo>()

    @Test
    fun `complex property is defined as a $ref`() {
      expectThat(schema.properties[Foo::bar.name])
        .isA<Ref>()
        .get { `$ref` }
        .isEqualTo("#/\$defs/${Bar::class.java.simpleName}")
    }

    @Test
    fun `referenced schema is included in $defs`() {
      expectThat(schema.`$defs`)
        .hasSize(1)
        .get(Bar::class.java.simpleName)
        .isA<ObjectSchema>()
        .get { properties }
        .get(Bar::string.name)
        .isA<StringSchema>()
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
      val string: String
    )

    val schema = generateSchema<Foo>()

    @Test
    fun `nested complex property is defined as a $ref`() {
      expectThat(schema.`$defs`)
        .get(Bar::class.java.simpleName)
        .isA<ObjectSchema>()
        .get { properties }
        .get(Bar::baz.name)
        .isA<Ref>()
        .get { `$ref` }
        .isEqualTo("#/\$defs/${Baz::class.java.simpleName}")
    }

    @Test
    fun `deep referenced schema is included in $defs`() {
      expectThat(schema.`$defs`)
        .hasSize(2)
        .containsKey(Bar::class.java.simpleName)
        .containsKey(Baz::class.java.simpleName)
        .get(Baz::class.java.simpleName)
        .isA<ObjectSchema>()
        .get { properties }
        .get(Baz::string.name)
        .isA<StringSchema>()
    }
  }

  @Nested
  @DisplayName("sealed classes")
  class SealedClasses {
    data class Foo(
      val bar: Bar
    )

    sealed class Bar {
      abstract val string: String

      data class Bar1(override val string: String) : Bar()
      data class Bar2(override val string: String) : Bar()
    }

    val schema = generateSchema<Foo>()

    @Test
    fun `sealed class property is a reference to the base type`() {
      expectThat(schema.properties[Foo::bar.name])
        .isA<Ref>()
        .get { `$ref` }
        .isEqualTo("#/\$defs/Bar")
    }

    @Test
    fun `sealed class definition is one of the sub-types`() {
      expectThat(schema.`$defs`[Bar::class.java.simpleName])
        .isA<OneOf>()
        .get { oneOf }
        .one { isA<Ref>().get { `$ref` }.isEqualTo("#/\$defs/${Bar.Bar1::class.java.simpleName}") }
        .one { isA<Ref>().get { `$ref` }.isEqualTo("#/\$defs/${Bar.Bar2::class.java.simpleName}") }
    }
  }

  @Nested
  @DisplayName("array like properties")
  class ArrayLikeProperties {
    data class Foo(
      val listOfStrings: List<String>,
      val setOfStrings: Set<String>,
      val listOfObjects: List<Baz>,
      @Suppress("ArrayInDataClass") val arrayOfStrings: Array<String>
    )

    data class Baz(
      val string: String
    )

    val schema = generateSchema<Foo>()

    @TestFactory
    fun arrayAndListProperties() =
      mapOf("list" to Foo::listOfStrings, "array" to Foo::arrayOfStrings)
        .map { (type, property) ->
          dynamicTest("$type property is an array of strings") {
            expectThat(schema.properties[property.name])
              .isA<ArraySchema>()
              .and {
                get { items }.isA<StringSchema>()
                get { uniqueItems }.isNull()
              }
          }
        }

    @Test
    fun `set property is an array of strings with unique items`() {
      expectThat(schema.properties[Foo::setOfStrings.name])
        .isA<ArraySchema>()
        .and {
          get { items }.isA<StringSchema>()
          get { uniqueItems }.isTrue()
        }
    }

    @Test
    fun `list of objects property is an array of refs`() {
      expectThat(schema.properties[Foo::listOfObjects.name])
        .isA<ArraySchema>()
        .and {
          get { items }.isA<Ref>()
        }
    }

    @Test
    fun `referenced schemas are defined`() {
      expectThat(schema.`$defs`[Baz::class.java.simpleName])
        .isA<ObjectSchema>()
    }
  }

  @Nested
  @DisplayName("map properties")
  class MapProperties {
    data class Foo(
      val mapOfStrings: Map<String, String>,
      val mapOfObjects: Map<String, Bar>
    )

    data class Bar(
      val string: String
    )

    val schema = generateSchema<Foo>()

    @Test
    fun `map property is represented as an object with additional properties according to its value type`() {
      expectThat(schema.properties[Foo::mapOfStrings.name])
        .isA<MapSchema>()
        .get { additionalProperties }
        .isA<StringSchema>()
    }

    @Test
    fun `map property with object values uses refs`() {
      expectThat(schema.properties[Foo::mapOfObjects.name])
        .isA<MapSchema>()
        .get { additionalProperties }
        .isA<Ref>()
        .get { `$ref` }
        .isEqualTo("#/\$defs/${Bar::class.java.simpleName}")
    }
  }

  @Nested
  @DisplayName("non-data classes")
  class NonDataClasses {
    class Foo(
      val constructorProperty: String,
      constructorParameter: String
    ) {
      val nonConstructorProperty: String
        get() = javaClass.canonicalName
    }

    val schema = generateSchema<Foo>()

    @Test
    fun `properties that are part of the constructor are documented`() {
      expectThat(schema.properties[Foo::constructorProperty.name])
        .isA<StringSchema>()
    }

    @Test
    fun `constructor parameters not backed by properties are documented`() {
      expectThat(schema.properties["constructorParameter"])
        .isA<StringSchema>()
    }

    @Test
    fun `properties that are not part of the constructor are not documented`() {
      expectThat(schema.properties[Foo::nonConstructorProperty.name])
        .isNull()
    }
  }

  @Nested
  @DisplayName("Java POJOs")
  class JavaPojos {
    val schema = generateSchema<JavaPojo>()

    @Test
    fun `constructor parameters are documented`() {
      expectThat(schema.properties)
        .containsKey("arg0")
        .get("arg0")
        .isA<StringSchema>()
    }
  }

  @Nested
  @DisplayName("date and time types")
  class DateAndTimeTypes {
    data class Foo(
      val instant: Instant,
      val localDate: LocalDate,
      val localTime: LocalTime,
      val duration: Duration
    )

    val schema = generateSchema<Foo>()

    @TestFactory
    fun dateAndTimeFormattedStringProperties() =
      mapOf(
        Foo::instant.name to "datetime",
        Foo::localDate.name to "date",
        Foo::localTime.name to "time",
        Foo::duration.name to "duration"
      ).map { (property, format) ->
        dynamicTest("$property property is a string with '$format' format") {
          expectThat(schema.properties[property])
            .isA<StringSchema>()
            .get { format }
            .isEqualTo(format)
        }
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
        .writeValueAsString(it)
        .also(::println)
    }
