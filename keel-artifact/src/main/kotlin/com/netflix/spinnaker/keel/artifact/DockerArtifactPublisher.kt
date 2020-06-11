package com.netflix.spinnaker.keel.artifact

import com.netflix.spinnaker.keel.api.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.api.artifacts.DockerVersioningStrategy
import com.netflix.spinnaker.keel.api.plugins.ArtifactPublisher
import com.netflix.spinnaker.keel.api.plugins.SupportedArtifact
import com.netflix.spinnaker.keel.api.plugins.SupportedVersioningStrategy
import com.netflix.spinnaker.keel.api.support.EventPublisher
import org.springframework.stereotype.Component

/**
 * Built-in keel implementation of [ArtifactPublisher] that does not itself receive/retrieve artifact information
 * but is used by keel's `POST /artifacts/events` API to notify the core of new Docker artifacts.
 */
@Component
class DockerArtifactPublisher(
  override val eventPublisher: EventPublisher
) : ArtifactPublisher {
  override val supportedArtifacts: List<SupportedArtifact<*>>
    get() = listOf(
      SupportedArtifact("docker", DockerArtifact::class.java)
    )

  override val supportedVersioningStrategies: List<SupportedVersioningStrategy<*>>
    get() = listOf(
      SupportedVersioningStrategy("docker", DockerVersioningStrategy::class.java)
    )
}
