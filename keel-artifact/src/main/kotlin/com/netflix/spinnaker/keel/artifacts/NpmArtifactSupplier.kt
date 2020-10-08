package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.NPM
import com.netflix.spinnaker.keel.api.artifacts.ArtifactInstance
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy
import com.netflix.spinnaker.keel.api.plugins.ArtifactSupplier
import com.netflix.spinnaker.keel.api.plugins.SupportedArtifact
import com.netflix.spinnaker.keel.api.plugins.SupportedVersioningStrategy
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.services.ArtifactMetadataService
import org.springframework.stereotype.Component

/**
 * Built-in keel implementation of [ArtifactSupplier] for NPM artifacts.
 *
 * Note: this implementation currently makes some Netflix-specific assumptions with regards to artifact
 * versions so that it can extract build and commit metadata.
 */
@Component
class NpmArtifactSupplier(
  override val eventPublisher: EventPublisher,
  private val artifactService: ArtifactService,
  override val artifactMetadataService: ArtifactMetadataService
) : BaseArtifactSupplier<NpmArtifactSpec, NpmVersioningStrategy>(artifactMetadataService) {

  override val supportedArtifact = SupportedArtifact(NPM, NpmArtifactSpec::class.java)

  override val supportedVersioningStrategy =
    SupportedVersioningStrategy(NPM, NpmVersioningStrategy::class.java)

  override fun getLatestArtifact(deliveryConfig: DeliveryConfig, artifact: ArtifactSpec): ArtifactInstance? =
    runWithIoContext {
      artifactService
        .getVersions(artifact.nameForQuery, artifact.statusesForQuery, NPM)
        .sortedWith(artifact.versioningStrategy.comparator)
        .firstOrNull() // versioning strategies return descending by default... ¯\_(ツ)_/¯
        ?.let { version ->
          artifactService.getArtifact(artifact.nameForQuery, version, NPM)
        }
    }

  override fun getArtifactByVersion(artifact: ArtifactSpec, version: String): ArtifactInstance? =
    runWithIoContext {
      artifactService.getArtifact(artifact.nameForQuery, version, NPM)
    }

  /**
   * Extracts a version display name from version string using the Netflix semver convention.
   */
  override fun getVersionDisplayName(artifact: ArtifactInstance): String {
    return NetflixVersions.getVersionDisplayName(artifact)
  }

  /**
   * Extracts the build number from the version string using the Netflix semver convention.
   */
  override fun parseDefaultBuildMetadata(artifact: ArtifactInstance, versioningStrategy: VersioningStrategy): BuildMetadata? {
    return NetflixVersions.getBuildNumber(artifact)
      ?.let { BuildMetadata(it) }
  }

  /**
   * Extracts the commit hash from the version string using the Netflix semver convention.
   */
  override fun parseDefaultGitMetadata(artifact: ArtifactInstance, versioningStrategy: VersioningStrategy): GitMetadata? {
    return NetflixVersions.getCommitHash(artifact)
      ?.let { GitMetadata(it) }
  }


  // The API requires colons in place of slashes to avoid path pattern conflicts
  private val ArtifactSpec.nameForQuery: String
    get() = name.replace("/", ":")

  private val ArtifactSpec.statusesForQuery: List<String>
    get() = statuses.map { it.name }
}
