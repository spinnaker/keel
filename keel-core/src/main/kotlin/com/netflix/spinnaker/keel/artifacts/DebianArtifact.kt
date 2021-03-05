package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.ArtifactOriginFilterSpec
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.BranchFilterSpec
import com.netflix.spinnaker.keel.api.artifacts.DEBIAN
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.SortingStrategy
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions

/**
 * A [DeliveryArtifact] that describes Debian packages.
 */
data class DebianArtifact(
  override val name: String,
  override val deliveryConfigName: String? = null,
  override val reference: String = name,
  override val statuses: Set<ArtifactStatus> = emptySet(),
  val vmOptions: VirtualMachineOptions,
  val branch: String? = null,
  override val from: ArtifactOriginFilterSpec? =
    branch?.let { ArtifactOriginFilterSpec(BranchFilterSpec(name = branch)) }
) : DeliveryArtifact() {
  override val type = DEBIAN

  override val sortingStrategy: SortingStrategy
    get() = if (filteredByBranch || filteredByPullRequest) {
      CreatedAtSortingStrategy
    } else {
      DebianVersionSortingStrategy
    }

  override fun toString(): String = super.toString()
}
