/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.exceptions.ArtifactParsingException
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.artifact.DEB
import com.netflix.spinnaker.keel.artifact.DOCKER
import com.netflix.spinnaker.keel.artifact.DebianArtifact
import com.netflix.spinnaker.keel.artifact.DockerArtifact
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper

private val objectMapper: ObjectMapper = configuredObjectMapper()

/**
 * A helper function to construct the proper artifact type from the serialized json.
 * FIXME: this needs to go away in favor of [ArtifactPublisher] functions to keep
 * artifact contracts generic.
 */
fun mapToArtifact(
  name: String,
  type: ArtifactType,
  json: String,
  reference: String,
  deliveryConfigName: String
): DeliveryArtifact {
  try {
    val details = objectMapper.readValue<Map<String, Any>>(json)
    return when (type) {
      DEB -> {
        val statuses: Set<ArtifactStatus> = details["statuses"]?.let { it ->
          try {
            objectMapper.convertValue<Set<ArtifactStatus>>(it)
          } catch (e: IllegalArgumentException) {
            null
          }
        } ?: emptySet()
        DebianArtifact(
          name = name,
          statuses = statuses,
          reference = reference,
          deliveryConfigName = deliveryConfigName,
          vmOptions = details["vmOptions"]?.let {
            objectMapper.convertValue<VirtualMachineOptions>(it)
          } ?: error("vmOptions is required")
        )
      }
      DOCKER -> {
        val tagVersionStrategy = details.getOrDefault("tagVersionStrategy", "semver-tag")
        DockerArtifact(
          name = name,
          tagVersionStrategy = objectMapper.convertValue(tagVersionStrategy),
          captureGroupRegex = details["captureGroupRegex"]?.toString(),
          reference = reference,
          deliveryConfigName = deliveryConfigName
        )
      }
      else -> error("Unrecognized artifact type $type")
    }
  } catch (e: JsonMappingException) {
    throw ArtifactParsingException(name, type, json, e)
  }
}
