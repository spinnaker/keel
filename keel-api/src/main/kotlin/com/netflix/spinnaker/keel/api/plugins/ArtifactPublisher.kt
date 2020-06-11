package com.netflix.spinnaker.keel.api.plugins

import com.netflix.spinnaker.keel.api.events.ArtifactEvent
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.kork.plugins.api.internal.SpinnakerExtensionPoint

/**
 * Keel plugin interface to be implemented by publishers of artifact information.
 *
 * The job of an [ArtifactPublisher] is to detect new versions of artifacts, using
 * whatever mechanism they choose (e.g. they could receive events from another system,
 * or poll an artifact repository for artifact versions), and notify keel via the [publishArtifact]
 * method, so that the artifact versions can be persisted and evaluated for promotion.
 *
 * The default implementation of [publishArtifact] simply publishes the event via the [EventPublisher],
 * and should *not* be overridden by implementors.
 */
interface ArtifactPublisher : SpinnakerExtensionPoint {
  val eventPublisher: EventPublisher

  val supportedArtifacts: List<SupportedArtifact<*>>

  val supportedVersioningStrategies: List<SupportedVersioningStrategy<*>>

  fun publishArtifact(artifactEvent: ArtifactEvent) =
    eventPublisher.publishEvent(artifactEvent)
}
