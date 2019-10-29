package com.netflix.spinnaker.keel.serialization

import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.ArtifactType
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.isEqualTo

internal class ArtifactTypeSerializationTests : JUnit5Minutests {

  val mapper = configuredYamlMapper()

  fun tests() = rootContext<Unit> {
    context("custom (de)serialization") {
      test("serializes enum value to friendly name") {
        for (enumValue in ArtifactType.values()) {
          expectThat(mapper.writeValueAsString(enumValue)).isEqualTo("--- \"${enumValue.friendlyName}\"\n")
        }
      }

      test("deserializes friendly name to enum value") {
        for (enumValue in ArtifactType.values()) {
          expectThat(mapper.readValue<ArtifactType>(enumValue.friendlyName)).isEqualTo(enumValue)
        }
      }
    }
  }
}
