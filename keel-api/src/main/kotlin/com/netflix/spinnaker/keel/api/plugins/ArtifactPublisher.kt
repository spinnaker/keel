package com.netflix.spinnaker.keel.api.plugins

import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.SpinnakerArtifact
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy
import com.netflix.spinnaker.keel.api.events.ArtifactEvent
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.kork.plugins.api.internal.SpinnakerExtensionPoint

/**
 * Keel plugin interface to be implemented by publishers of artifact information.
 *
 * The main job of an [ArtifactPublisher] is to detect new versions of artifacts, using
 * whatever mechanism they choose (e.g. they could receive events from another system,
 * or poll an artifact repository for artifact versions), and notify keel via the [publishArtifact]
 * method, so that the artifact versions can be persisted and evaluated for promotion.
 *
 * The default implementation of [publishArtifact] simply publishes the event via the [EventPublisher],
 * and should *not* be overridden by implementors.
 */
interface ArtifactPublisher<T : DeliveryArtifact> : SpinnakerExtensionPoint {
  val eventPublisher: EventPublisher
  val supportedArtifact: SupportedArtifact<T>
  val supportedVersioningStrategies: List<SupportedVersioningStrategy<*>>

  fun publishArtifact(artifactEvent: ArtifactEvent) =
    eventPublisher.publishEvent(artifactEvent)

  /**
   * Returns the latest available version for the given [DeliveryArtifact], represented
   * as a [SpinnakerArtifact].
   *
   * This function may interact with external systems to retrieve artifact information as needed.
   */
  suspend fun getLatestArtifact(artifact: DeliveryArtifact): SpinnakerArtifact?

  // Utility functions to help convert a kork Artifact to a DeliveryArtifact
  // Ideally these should be "static" and still overridden in sub-classes, but there's no such
  // concept in Kotlin (or any other JVM language for that matter).
  fun getFullVersionString(artifact: SpinnakerArtifact): String = artifact.version
  fun getVersionDisplayName(artifact: SpinnakerArtifact): String = artifact.version
  fun getReleaseStatus(artifact: SpinnakerArtifact): ArtifactStatus? = null
  fun getBuildMetadata(artifact: SpinnakerArtifact, versioningStrategy: VersioningStrategy): BuildMetadata? = null
  fun getGitMetadata(artifact: SpinnakerArtifact, versioningStrategy: VersioningStrategy): GitMetadata? = null
}

fun List<ArtifactPublisher<*>>.supporting(type: ArtifactType) =
  find { it.supportedArtifact.name == type }
    ?: error("Artifact type '$type' is not supported.")
