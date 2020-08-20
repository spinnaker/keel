package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.ArtifactMetadata
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.services.ArtifactMetadataService
import org.slf4j.LoggerFactory

abstract class ArtifactSupplierBaseClass (
  open val artifactMetadataService: ArtifactMetadataService
) {
   suspend fun getArtifactMetadataInternal(artifact: PublishedArtifact): ArtifactMetadata? {
    val buildNumber = artifact.metadata["buildNumber"]?.toString()
    val commitId = artifact.metadata["commitId"]?.toString()
    if (commitId == null || buildNumber == null) {
      return null
    }
     log.debug("calling to artifact metadata service to get information for artifact: ${artifact.reference}, version: ${artifact.version}, type: ${artifact.type} " +
       "with build number: $buildNumber and commit id: $commitId")
    return artifactMetadataService.getArtifactMetadata(buildNumber, commitId)
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
