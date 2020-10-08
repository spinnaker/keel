package com.netflix.spinnaker.keel.api.events

import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec
import com.netflix.spinnaker.keel.api.artifacts.ArtifactInstance

/**
 * An event that conveys information about one or more [ArtifactInstance] that are
 * potentially relevant to keel.
 */
data class ArtifactPublishedEvent(
  val artifacts: List<ArtifactInstance>,
  val details: Map<String, Any>? = emptyMap()
)

/**
 * Event emitted with a new [ArtifactSpec] is registered.
 */
data class ArtifactRegisteredEvent(
  val artifact: ArtifactSpec
)

/**
 * Event emitted to trigger synchronization of artifact information.
 */
data class ArtifactSyncEvent(
  val controllerTriggered: Boolean = false
)

/**
 * An event fired to signal that an artifact version is deploying to a resource.
 */
data class ArtifactVersionDeploying(
  val resourceId: String,
  val artifactVersion: String
)

/**
 * An event fired to signal that an artifact version was successfully deployed to a resource.
 */
data class ArtifactVersionDeployed(
  val resourceId: String,
  val artifactVersion: String
)
