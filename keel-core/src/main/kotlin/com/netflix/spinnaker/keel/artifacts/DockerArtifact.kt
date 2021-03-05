package com.netflix.spinnaker.keel.artifacts

import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.keel.api.artifacts.ArtifactOriginFilterSpec
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.BranchFilterSpec
import com.netflix.spinnaker.keel.api.artifacts.DOCKER
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.SEMVER_TAG
import com.netflix.spinnaker.keel.api.artifacts.SortingStrategy
import com.netflix.spinnaker.kork.web.exceptions.ValidationException

/**
 * A [DeliveryArtifact] that describes Docker images.
 */
data class DockerArtifact(
  override val name: String,
  override val deliveryConfigName: String? = null,
  override val reference: String = name,
  val tagVersionStrategy: TagVersionStrategy? = null,
  val captureGroupRegex: String? = null,
  val branch: String? = null,
  override val from: ArtifactOriginFilterSpec? =
    branch?.let { ArtifactOriginFilterSpec(BranchFilterSpec(name = branch)) }
) : DeliveryArtifact() {
  init {
    require(name.count { it == '/' } <= 1) {
      "Docker image name has more than one slash, which is not Docker convention. Please convert to `organization/image-name` format."
    }
    require(from == null || tagVersionStrategy == null) {
      "Either `from` or `tagVersionStrategy` must be specified in the delivery config for Docker artifacts. Please check the documentation."
    }
  }

  override val type = DOCKER

  @JsonIgnore
  val organization: String = name.substringBefore('/')

  @JsonIgnore
  val image: String = name.substringAfter('/')

  @JsonIgnore
  override val statuses: Set<ArtifactStatus> = emptySet()

  override val sortingStrategy: SortingStrategy
    get() = if (filteredByBranch || filteredByPullRequest) {
      CreatedAtSortingStrategy
    } else if (tagVersionStrategy != null) {
      DockerVersionSortingStrategy(tagVersionStrategy, captureGroupRegex)
    } else throw ValidationException(
      listOf("Unable to determine sorting strategy for $this. You must specify either `tagVersionStrategy` or `from` in your delivery config."))

  override fun toString(): String = super.toString()
}
