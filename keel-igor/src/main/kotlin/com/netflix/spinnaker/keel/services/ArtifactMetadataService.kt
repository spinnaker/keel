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

  /**
   * Returns additional metadata about the specified build and commit, if available. This call is configured
   * to auto-retry as it's not on a code path where any external retries would happen.
   */
  @Retry(name = "getArtifactMetadata", fallbackMethod = "fallback")
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
        number = number.toString(),
        result = result.toString(),
        duration = duration
      ),
      GitMetadata(
        commit = commitId,
        author = scm?.first()?.committer,
        commitMessage = scm?.first()?.message,
        branchName = scm?.first()?.branch,
        projectName = properties?.get("projectKey") as String?,
        repoName = properties?.get("repoSlug") as String?,
        pullRequestNumber = properties?.get("pullRequestNumber") as String?,
        pullRequestUrl = properties?.get("pullRequestUrl") as String?,
        linkToCommit = ""
      )
    )


  //this method will be invoked whenever the retry will fail
  protected fun fallback( buildNumber: String, commitId: String, e: Exception) {
    log.error("received an error while calling artifact service for build number $buildNumber and commit id $commitId", e)
    throw e
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
