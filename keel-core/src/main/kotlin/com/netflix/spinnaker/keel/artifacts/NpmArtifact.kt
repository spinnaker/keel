package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.SortStrategy
import com.netflix.spinnaker.keel.api.artifacts.SortStrategy.VERSION
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.NPM
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy

/**
 * A [DeliveryArtifact] that describes NPM packages.
 */
data class NpmArtifact(
  override val name: String,
  override val deliveryConfigName: String? = null,
  override val reference: String = name,
  override val statuses: Set<ArtifactStatus> = emptySet(),
  override val versioningStrategy: VersioningStrategy = NpmVersioningStrategy,
  override val sortBy: SortStrategy = VERSION,
  override val branch: String? = null
) : DeliveryArtifact() {
  override val type = NPM
  override fun toString(): String = super.toString()
}
