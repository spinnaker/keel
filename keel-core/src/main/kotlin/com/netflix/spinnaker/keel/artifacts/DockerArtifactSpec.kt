package com.netflix.spinnaker.keel.artifacts

import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.keel.api.artifacts.ArtifactOriginFilterSpec
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.DOCKER
import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.SEMVER_TAG
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy

/**
 * A [ArtifactSpec] that describes Docker images.
 */
data class DockerArtifactSpec(
  override val name: String,
  override val deliveryConfigName: String? = null,
  override val reference: String = name,
  val tagVersionStrategy: TagVersionStrategy = SEMVER_TAG,
  val captureGroupRegex: String? = null,
  override val versioningStrategy: VersioningStrategy = DockerVersioningStrategy(tagVersionStrategy, captureGroupRegex),
  override val from: ArtifactOriginFilterSpec? = null
) : ArtifactSpec() {
  init {
    require(name.count { it == '/' } <= 1) {
      "Docker image name has more than one slash, which is not Docker convention. Please convert to `organization/image-name` format."
    }
  }

  override val type = DOCKER

  @JsonIgnore
  val organization: String = name.substringBefore('/')

  @JsonIgnore
  val image: String = name.substringAfter('/')

  @JsonIgnore
  override val statuses: Set<ArtifactStatus> = emptySet()
  override fun toString(): String = super.toString()
}
