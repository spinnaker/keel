package com.netflix.spinnaker.keel.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonProperty.Access
import com.netflix.spinnaker.keel.api.ArtifactType.deb
import com.netflix.spinnaker.keel.api.ArtifactType.docker
import com.netflix.spinnaker.keel.api.TagVersionStrategy.SEMVER_TAG

enum class ArtifactType {
  deb, docker;
}

enum class ArtifactStatus {
  FINAL, CANDIDATE, SNAPSHOT, RELEASE
}

sealed class DeliveryArtifact {
  abstract val name: String
  abstract val type: ArtifactType
  abstract val versioningStrategy: VersioningStrategy
  abstract val reference: String // friendly reference to use within a delivery config
  abstract val deliveryConfigName: String? // the delivery config this artifact is a part of
}

data class DebianArtifact(
  override val name: String,
  @JsonProperty(access = Access.WRITE_ONLY) override val deliveryConfigName: String? = null,
  override val reference: String = name,
  val statuses: List<ArtifactStatus> = emptyList(),
  @JsonProperty(access = Access.WRITE_ONLY) override val versioningStrategy: VersioningStrategy = DebianSemVerVersioningStrategy
) : DeliveryArtifact() {
  override val type = deb
}

data class DockerArtifact(
  override val name: String,
  @JsonProperty(access = Access.WRITE_ONLY) override val deliveryConfigName: String? = null,
  override val reference: String = name,
  val tagVersionStrategy: TagVersionStrategy = SEMVER_TAG,
  val captureGroupRegex: String? = null,
  @JsonProperty(access = Access.WRITE_ONLY) override val versioningStrategy: VersioningStrategy = DockerVersioningStrategy(tagVersionStrategy, captureGroupRegex)
) : DeliveryArtifact() {
  override val type = docker
}

val DockerArtifact.organization
  get() = name.split("/").first()

val DockerArtifact.image
  get() = name.split("/").last()
