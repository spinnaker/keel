package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.ArtifactOriginFilter
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.NPM
import com.netflix.spinnaker.keel.api.artifacts.SortingStrategy

/**
 * A [DeliveryArtifact] that describes NPM packages.
 */
data class NpmArtifact(
  override val name: String,
  override val deliveryConfigName: String? = null,
  override val reference: String = name,
  override val statuses: Set<ArtifactStatus> = emptySet(),
  override val from: ArtifactOriginFilter? = null
) : DeliveryArtifact() {
  override val type = NPM

  override val sortingStrategy: SortingStrategy
    get() = if (filteredBySource) {
      CreatedAtSortingStrategy
    } else {
      NpmVersionSortingStrategy
    }

  override fun toString(): String = super.toString()
}
