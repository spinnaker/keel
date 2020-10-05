package com.netflix.spinnaker.keel.api.artifacts

import com.netflix.spinnaker.keel.api.schema.Discriminator
import java.time.Instant

typealias ArtifactType = String

const val DEBIAN: ArtifactType = "deb"
const val DOCKER: ArtifactType = "docker"
const val NPM: ArtifactType = "npm"

/**
 * The release status of an artifact. This may not necessarily be applicable to all
 * [DeliveryArtifact] sub-classes.
 */
enum class ArtifactStatus {
  FINAL, CANDIDATE, SNAPSHOT, RELEASE, UNKNOWN
}

/**
 * An artifact as defined in a [DeliveryConfig].
 *
 * Unlike other places within Spinnaker, this class does not describe a specific instance of a software artifact
 * (i.e. the output of a build that is published to an artifact repository), but rather the high-level properties
 * that allow keel and [ArtifactSupplier] plugins to find/process the actual artifacts.
 */
// TODO: rename to `ArtifactSpec`
abstract class DeliveryArtifact {
  abstract val name: String
  @Discriminator abstract val type: ArtifactType
  abstract val versioningStrategy: VersioningStrategy
  /** A friendly reference to use within a delivery config. */
  abstract val reference: String
  /** The delivery config this artifact is a part of. */
  abstract val deliveryConfigName: String?
  /** A set of release statuses to filter by. Mutually exclusive with [fromBranch] and [fromPullRequest]. */
  open val statuses: Set<ArtifactStatus> = emptySet()
  /** A specific branch to filter by, or a regular expression like "feature-.*" */
  open val fromBranch: String? = null
  /** Whether to include only artifacts from pull requests. */
  open val fromPullRequest: Boolean? = false

  val filteredByBranch: Boolean
    get() = fromBranch != null

  val filteredByPullRequest: Boolean
    get() = fromPullRequest == true

  val filteredByReleaseStatus: Boolean
    get() = statuses.isNotEmpty()

  fun toArtifactInstance(version: String, status: ArtifactStatus? = null, createdAt: Instant? = null) =
    PublishedArtifact(
      name = name,
      type = type,
      reference = reference,
      version = version,
      metadata = mapOf(
        "releaseStatus" to status,
        "createdAt" to createdAt
      )
    ).normalized()

  override fun toString() = "${type.toUpperCase()} artifact $name (ref: $reference)"
}
