package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.igor.BuildService
import com.netflix.spinnaker.keel.api.artifacts.ArtifactMetadata
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component

/**
 * Provides functionality to convert build metadata, which is coming from internal service, to artfiact metadata (via igor).
 */
@Component
class ArtifactMetadataService(
  private val buildService: BuildService
) {

  fun getArtifactMetadata(
    commitId: String?,
    buildNumber: String?
  ): ArtifactMetadata? {

    if (commitId == null || buildNumber == null) {
      return null
    }
      val builds = runBlocking {
        buildService.getArtifactMetadata(commitId = commitId, buildNumber = buildNumber)
    }

    // TODO: implement the exact conversion between build service response to the objects we are expecting
    return ArtifactMetadata(
      buildMetadata = BuildMetadata(id = builds[0].number),
      gitMetadata = GitMetadata(commit = commitId)
    )
  }
}
