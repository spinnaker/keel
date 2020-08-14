package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.plugins.ArtifactSupplier
import com.netflix.spinnaker.keel.api.plugins.supporting
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.telemetry.ArtifactSaved
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ArtifactStoredListener(
  private val repository: KeelRepository,
  private val artifactSuppliers: List<ArtifactSupplier<*, *>>
) {

  @EventListener(ArtifactSaved::class)
  suspend fun onArtifactSaved(event: ArtifactSaved) {
    val artifactSupplier = artifactSuppliers.supporting(event.artifact.type)
    val artifactMetadata = artifactSupplier.getArtifactMetadata(event.artifact)
    repository.updateArtifactMetadata(event.artifact.name, event.artifact.type, event.artifact.version, event.artifactStatus, artifactMetadata)
  }
}
