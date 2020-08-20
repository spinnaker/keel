package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.igor.BuildService
import com.netflix.spinnaker.keel.api.artifacts.ArtifactMetadata
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.model.Build
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import retrofit2.HttpException

/**
 * Provides functionality to convert build metadata, which is coming from internal service, to artifact metadata (via igor).
 */
@Component
class ArtifactMetadataService(
  private val buildService: BuildService
) {

  @Retry(name = "getArtifactMetadata", fallbackMethod = "getFallback")
  suspend fun getArtifactMetadata(
    buildNumber: String,
    commitId: String
  ): ArtifactMetadata? {

    try {
      val builds =
        buildService.getArtifactMetadata(commitId = commitId, buildNumber = buildNumber)

      if (builds.isNullOrEmpty()) {
        return null
      }

      return builds.first().toArtifactMetadata(commitId)
    } catch (e: HttpException) {
          log.warn(
            "Exception ${e.message} has caught while calling ci provider to fetch details for commit id: $commitId and buildNumber $buildNumber" +
              " Going to retry...",
            e
          )
      }
    return null
  }

  private fun Build.toArtifactMetadata(commitId: String) =
    ArtifactMetadata(
      BuildMetadata(
        id = number,
        jobName = name,
        uid = id,
        startedAt = properties?.get("startedAt") as String?,
        completedAt = properties?.get("completedAt") as String?,
        jobUrl = url,
        number = number.toString()
      ),
      GitMetadata(
        commit = commitId,
        author = properties?.get("author") as String?,
        commitMessage = properties?.get("message") as String?,
        linkToCommit = "",
        projectName = properties?.get("projectKey") as String?,
        repoName = properties?.get("repoSlug") as String?
      )
    )

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
