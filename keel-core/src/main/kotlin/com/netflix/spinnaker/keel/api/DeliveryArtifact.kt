package com.netflix.spinnaker.keel.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.As
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.annotation.JsonValue
import com.netflix.spinnaker.keel.api.ArtifactType.DEB
import com.netflix.spinnaker.keel.api.ArtifactType.DOCKER
import com.netflix.spinnaker.keel.api.TagVersionStrategy.SEMVER_TAG

enum class ArtifactType {
  DEB, DOCKER;

  @JsonValue
  fun value(): String = name.toLowerCase()
}

enum class ArtifactStatus {
  FINAL, CANDIDATE, SNAPSHOT, RELEASE
}

@JsonTypeInfo(
  use = Id.NAME,
  include = As.EXISTING_PROPERTY,
  property = "type"
)
@JsonSubTypes(
  Type(DebianArtifact::class),
  Type(DockerArtifact::class)
)
interface DeliveryArtifact {
  val name: String
  val type: ArtifactType
  val versioningStrategy: VersioningStrategy
  val reference: String // friendly reference to use within a delivery config
  val deliveryConfigName: String? // the delivery config this artifact is a part of
}

@JsonTypeName("deb")
data class DebianArtifact(
  override val name: String,
  override val deliveryConfigName: String? = null,
  override val reference: String = name,
  val statuses: List<ArtifactStatus> = emptyList(),
  override val versioningStrategy: VersioningStrategy = DebianSemVerVersioningStrategy
) : DeliveryArtifact {
  override val type = DEB
}

@JsonTypeName("docker")
data class DockerArtifact(
  override val name: String,
  override val deliveryConfigName: String? = null,
  override val reference: String = name,
  val tagVersionStrategy: TagVersionStrategy = SEMVER_TAG,
  val captureGroupRegex: String? = null,
  override val versioningStrategy: VersioningStrategy = DockerVersioningStrategy(tagVersionStrategy, captureGroupRegex)
) : DeliveryArtifact {
  override val type = DOCKER

  val organization
    get() = name.split("/").first()
  val image
    get() = name.split("/").last()
}
