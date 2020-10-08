package com.netflix.spinnaker.keel.test

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.netflix.spinnaker.keel.artifacts.DebianArtifactSpec
import com.netflix.spinnaker.keel.artifacts.DockerArtifactSpec
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.keel.serialization.configuredYamlMapper

fun configuredTestObjectMapper() = configuredObjectMapper()
  .registerArtifactSubtypes()

fun configuredTestYamlMapper() = configuredYamlMapper()
  .registerArtifactSubtypes()

private fun ObjectMapper.registerArtifactSubtypes() =
  this.apply {
    registerSubtypes(
      NamedType(DebianArtifactSpec::class.java, "deb"),
      NamedType(DockerArtifactSpec::class.java, "docker")
    )
  }
