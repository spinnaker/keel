package com.netflix.spinnaker.keel.api.artifacts

enum class ArtifactType {
  deb, docker;

  override fun toString() = name
}

enum class ArtifactStatus {
  FINAL, CANDIDATE, SNAPSHOT, RELEASE, UNKNOWN
}

/**
 * todo eb: other information should go here, like a link to the jenkins build. But that needs to be done
 * in a scalable way. For now, this is just a minimal container for information we can parse from the version.
 */
data class BuildMetadata(
  val id: Int
)

/**
 * todo eb: other information should go here, like a link to the commit. But that needs to be done
 * in a scalable way. For now, this is just a minimal container for information we can parse from the version.
 */
data class GitMetadata(
  val commit: String
)

abstract class DeliveryArtifact {
  abstract val name: String
  abstract val type: ArtifactType
  abstract val versioningStrategy: VersioningStrategy
  abstract val reference: String // friendly reference to use within a delivery config
  abstract val deliveryConfigName: String? // the delivery config this artifact is a part of
  override fun toString() = "$type:$name (ref: $reference)"
}
