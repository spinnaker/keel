package com.netflix.spinnaker.keel.artifact

import com.netflix.frigga.ami.AppVersion
import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.DebianSemVerVersioningStrategy
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.KorkArtifact
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy
import com.netflix.spinnaker.keel.api.plugins.ArtifactPublisher
import com.netflix.spinnaker.keel.api.plugins.SupportedArtifact
import com.netflix.spinnaker.keel.api.plugins.SupportedVersioningStrategy
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.core.comparator
import com.netflix.spinnaker.kork.exceptions.IntegrationException
import org.springframework.stereotype.Component

/**
 * Built-in keel implementation of [ArtifactPublisher] that does not itself receive/retrieve artifact information
 * but is used by keel's `POST /artifacts/events` API to notify the core of new Debian artifacts.
 */
@Component
class DebianArtifactPublisher(
  override val eventPublisher: EventPublisher,
  private val artifactService: ArtifactService
) : ArtifactPublisher<DebianArtifact> {
  override val supportedArtifact = SupportedArtifact("deb", DebianArtifact::class.java)

  override val supportedVersioningStrategies: List<SupportedVersioningStrategy<*>>
    get() = listOf(
      SupportedVersioningStrategy("deb", DebianSemVerVersioningStrategy::class.java)
    )

  override suspend fun getLatestArtifact(artifact: DeliveryArtifact): KorkArtifact? =
    artifactService
      .getVersions(artifact.name)
      .sortedWith(artifact.versioningStrategy.comparator)
      .firstOrNull() // versioning strategies return descending by default... ¯\_(ツ)_/¯
      ?.let { version ->
        val normalizedVersion = if (version.startsWith("${artifact.name}-")) {
          version.removePrefix("${artifact.name}-")
        } else {
          version
        }
        artifactService.getArtifact(artifact.name, normalizedVersion)
      }

  override fun getFullVersionString(artifact: KorkArtifact): String =
    "${artifact.name}-${artifact.version}"

  /**
   * Parses the status from a kork artifact, and throws an error if [releaseStatus] isn't
   * present in [metadata]
   */
  override fun getReleaseStatus(artifact: KorkArtifact): ArtifactStatus {
    val status = artifact.metadata["releaseStatus"]?.toString()
      ?: throw IntegrationException("Artifact metadata does not contain 'releaseStatus' field")
    return ArtifactStatus.valueOf(status)
  }

  override fun getVersionDisplayName(artifact: KorkArtifact): String {
    val appversion = AppVersion.parseName(artifact.version)
    return if (appversion?.version != null) {
      appversion.version
    } else {
      artifact.version.removePrefix("${artifact.name}-")
    }
  }

  override fun getBuildMetadata(artifact: KorkArtifact, versioningStrategy: VersioningStrategy): BuildMetadata? {
    // attempt to parse helpful info from the appversion.
    // todo(eb): replace, this is brittle
    val appversion = AppVersion.parseName(artifact.version)
    if (appversion?.buildNumber != null) {
      return BuildMetadata(id = appversion.buildNumber.toInt())
    }
    return null
  }

  override fun getGitMetadata(artifact: KorkArtifact, versioningStrategy: VersioningStrategy): GitMetadata? {
    // attempt to parse helpful info from the appversion.
    // todo(eb): replace, this is brittle
    val appversion = AppVersion.parseName(artifact.version)
    if (appversion?.commit != null) {
      return GitMetadata(commit = appversion.commit)
    }
    return null
  }
}
