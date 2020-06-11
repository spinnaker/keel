package com.netflix.spinnaker.keel.artifact

import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.DebianSemVerVersioningStrategy
import com.netflix.spinnaker.keel.api.plugins.ArtifactPublisher
import com.netflix.spinnaker.keel.api.plugins.SupportedArtifact
import com.netflix.spinnaker.keel.api.plugins.SupportedVersioningStrategy
import com.netflix.spinnaker.keel.api.support.EventPublisher
import org.springframework.stereotype.Component

/**
 * Built-in keel implementation of [ArtifactPublisher] that does not itself receive/retrieve artifact information
 * but is used by keel's `POST /artifacts/events` API to notify the core of new Debian artifacts.
 */
@Component
class DebianArtifactPublisher(
  override val eventPublisher: EventPublisher
) : ArtifactPublisher {
  override val supportedArtifacts: List<SupportedArtifact<*>>
    get() = listOf(
      SupportedArtifact("deb", DebianArtifact::class.java)
    )

  override val supportedVersioningStrategies: List<SupportedVersioningStrategy<*>>
    get() = listOf(
      SupportedVersioningStrategy("deb", DebianSemVerVersioningStrategy::class.java)
    )
}
