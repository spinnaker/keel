package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.ArtifactOriginFilterSpec
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec
import com.netflix.spinnaker.keel.api.artifacts.NPM
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy

/**
 * A [ArtifactSpec] that describes NPM packages.
 */
data class NpmArtifactSpec(
  override val name: String,
  override val deliveryConfigName: String? = null,
  override val reference: String = name,
  override val statuses: Set<ArtifactStatus> = emptySet(),
  override val versioningStrategy: VersioningStrategy = NpmVersioningStrategy,
  override val from: ArtifactOriginFilterSpec? = null
) : ArtifactSpec() {
  override val type = NPM
  override fun toString(): String = super.toString()
}
