package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec

data class DeliveryConfig(
  val application: String,
  val name: String,
  val serviceAccount: String,
  val artifacts: Set<ArtifactSpec> = emptySet(),
  val environments: Set<Environment> = emptySet(),
  val apiVersion: String = "delivery.config.spinnaker.netflix.com/v1",
  val metadata: Map<String, Any?> = emptyMap()
) {
  val resources: Set<Resource<*>>
    get() = environments.flatMapTo(mutableSetOf()) { it.resources }

  fun matchingArtifactByReference(reference: String): ArtifactSpec? =
    artifacts.find { it.reference == reference }

  fun matchingArtifactByName(name: String, type: ArtifactType): ArtifactSpec? =
    artifacts.find { it.name == name && it.type == type }
}
