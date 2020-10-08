package com.netflix.spinnaker.keel.artifacts

import com.netflix.frigga.ami.AppVersion
import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.DEBIAN
import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.ArtifactInstance
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy
import com.netflix.spinnaker.keel.api.plugins.ArtifactSupplier
import com.netflix.spinnaker.keel.api.plugins.SupportedArtifact
import com.netflix.spinnaker.keel.api.plugins.SupportedVersioningStrategy
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.services.ArtifactMetadataService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Built-in keel implementation of [ArtifactSupplier] for Debian artifacts.
 *
 * Note: this implementation currently makes some Netflix-specific assumptions with regards to artifact
 * versions so that it can extract build and commit metadata.
 */
@Component
class DebianArtifactSupplier(
  override val eventPublisher: EventPublisher,
  private val artifactService: ArtifactService,
  override val artifactMetadataService: ArtifactMetadataService
) : BaseArtifactSupplier<DebianArtifactSpec, DebianVersioningStrategy>(artifactMetadataService) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override val supportedArtifact = SupportedArtifact("deb", DebianArtifactSpec::class.java)

  override val supportedVersioningStrategy =
    SupportedVersioningStrategy("deb", DebianVersioningStrategy::class.java)

  override fun publishArtifact(artifact: ArtifactInstance) {
    if (artifact.hasReleaseStatus()) {
      super.publishArtifact(artifact)
    } else {
      log.debug("Ignoring artifact event without release status: $artifact")
    }
  }

  override fun getLatestArtifact(deliveryConfig: DeliveryConfig, artifact: ArtifactSpec): ArtifactInstance? =
    runWithIoContext {
      artifactService.getVersions(artifact.name, artifact.statusesForQuery, DEBIAN)
        .map { version -> "${artifact.name}-$version" }
        .sortedWith(artifact.versioningStrategy.comparator)
        .firstOrNull() // versioning strategies return descending by default... ¯\_(ツ)_/¯
        ?.let { version ->
          artifactService.getArtifact(artifact.name, version.removePrefix("${artifact.name}-"), DEBIAN)
        }
    }

  override fun getArtifactByVersion(artifact: ArtifactSpec, version: String): ArtifactInstance? =
    runWithIoContext {
      artifactService.getArtifact(artifact.name, version.removePrefix("${artifact.name}-"), DEBIAN)
    }

  override fun getVersionDisplayName(artifact: ArtifactInstance): String {
    // TODO: Frigga and Rocket version parsing are not aligned. We should consolidate.
    val appversion = AppVersion.parseName(artifact.version)
    return if (appversion?.version != null) {
      appversion.version
    } else {
      artifact.version.removePrefix("${artifact.name}-")
    }
  }

  override fun parseDefaultBuildMetadata(artifact: ArtifactInstance, versioningStrategy: VersioningStrategy): BuildMetadata? {
    // attempt to parse helpful info from the appversion.
    // TODO: Frigga and Rocket version parsing are not aligned. We should consolidate.
    val appversion = AppVersion.parseName(artifact.version)
    if (appversion?.buildNumber != null) {
      return BuildMetadata(id = appversion.buildNumber.toInt())
    }
    return null
  }

  override fun parseDefaultGitMetadata(artifact: ArtifactInstance, versioningStrategy: VersioningStrategy): GitMetadata? {
    // attempt to parse helpful info from the appversion.
    // TODO: Frigga and Rocket version parsing are not aligned. We should consolidate.
    val appversion = AppVersion.parseName(artifact.version)
    if (appversion?.commit != null) {
      return GitMetadata(commit = appversion.commit)
    }
    return null
  }


  // Debian Artifacts should contain a releaseStatus in the metadata
  private fun ArtifactInstance.hasReleaseStatus() =
    this.metadata.containsKey("releaseStatus") && this.metadata["releaseStatus"] != null

  private val ArtifactSpec.statusesForQuery: List<String>
    get() = statuses.map { it.name }
}
