package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.api.artifacts.ArtifactType

/**
 * Implemented by [ResourceSpec] or concrete resource types that (may) contain artifact information, typically compute
 * resources.
 *
 * The fields on this interface are nullable because typically the spec will _not_ have that information available
 * before the corresponding [ResourceHandler] has resolved the resource.
 */
interface ArtifactProvider {
  val artifactName: String?
  val artifactType: ArtifactType?
}

/**
 * Implemented by [ResourceSpec] or concrete resource types that (may) contain versioned artifacts, typically compute
 * resources.
 */
interface VersionedArtifactProvider : ArtifactProvider {
  val artifactVersion: String?

  fun completeVersionedArtifactOrNull() =
    if (artifactName != null && artifactType != null && artifactVersion != null) {
      VersionedArtifact(artifactName!!, artifactType!!, artifactVersion!!)
    } else {
      null
    }
}

/**
 * Implemented by [ResourceSpec] or concrete resource types that (may) contain artifact references, typically compute
 * resources.
 */
interface ArtifactReferenceProvider {
  val artifactReference: String?
  val artifactType: ArtifactType?

  fun completeArtifactReferenceOrNull() =
    if (artifactReference != null && artifactType != null) {
      ArtifactReference(artifactReference!!, artifactType!!)
    } else {
      null
    }
}

interface ComputeResourceSpec : ResourceSpec, VersionedArtifactProvider, ArtifactReferenceProvider

/**
 * Simple container of the information defined by [VersionedArtifactProvider] which ensures non-nullability of the
 * fields.
 */
data class VersionedArtifact(
  override val artifactName: String,
  override val artifactType: ArtifactType,
  override val artifactVersion: String
) : VersionedArtifactProvider

/**
 * Simple container of the information defined by [ArtifactReferenceProvider] which ensures non-nullability of the
 * fields.
 */
data class ArtifactReference(
  override val artifactReference: String,
  override val artifactType: ArtifactType
) : ArtifactReferenceProvider
