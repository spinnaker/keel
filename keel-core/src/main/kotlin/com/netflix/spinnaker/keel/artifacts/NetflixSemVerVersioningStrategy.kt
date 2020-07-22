package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy
import com.netflix.spinnaker.keel.core.NETFLIX_SEMVER_COMPARATOR

/**
 * A [VersioningStrategy] that codifies the versioning scheme conventions at Netflix.
 */
object NetflixSemVerVersioningStrategy : VersioningStrategy {
  override val type: String = "netflix-semver"

  override val comparator: Comparator<String> =
    NETFLIX_SEMVER_COMPARATOR

  private val NETFLIX_VERSION_REGEX = Regex("(\\d+\\.\\d+\\.\\d+(-rc(\\.\\d+)?)?)(-[h]?\\w+)?(-\\w+)?")
  private val BUILD_REGEX = Regex("-[h]?")

  /**
   * Extracts a version display name from the longer version string, leaving out build and git details if present.
   */
  fun getVersionDisplayName(artifact: PublishedArtifact): String {
    return NETFLIX_VERSION_REGEX.find(artifact.version)?.groups?.get(1)?.value
      ?: artifact.version
  }

  /**
   * Extracts the build number from the version string, if available.
   */
  fun getBuildNumber(artifact: PublishedArtifact): Int? {
    return NETFLIX_VERSION_REGEX.find(artifact.version)?.groups?.get(4)?.value
      ?.let {
        it.replace(BUILD_REGEX, "")
      }?.toInt()
  }

  /**
   * Extracts the commit hash from the version string, if available.
   */
  fun getCommitHash(artifact: PublishedArtifact): String? {
    return NETFLIX_VERSION_REGEX.find(artifact.version)?.groups?.get(5)?.value
      ?.let { it.replace("-", "") }
  }

  override fun toString(): String =
    javaClass.simpleName

  override fun equals(other: Any?): Boolean {
    return other is NetflixSemVerVersioningStrategy
  }
}
