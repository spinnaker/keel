package com.netflix.spinnaker.keel.test

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.keel.serialization.configuredYamlMapper

fun configuredTestObjectMapper(): ObjectMapper = configuredObjectMapper()
  .registerArtifactSubtypes()

fun configuredTestYamlMapper(): YAMLMapper = configuredYamlMapper()
  .registerArtifactSubtypes() as YAMLMapper

private fun ObjectMapper.registerArtifactSubtypes() =
  this.apply {
    registerSubtypes(
      NamedType(DebianArtifact::class.java, "deb"),
      NamedType(DockerArtifact::class.java, "docker")
    )
  }
