package com.netflix.spinnaker.keel.api.plugins

import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec

/**
 * Tuple of [ArtifactSpec] type name (e.g. "deb", "docker", etc.) and the
 * corresponding [ArtifactSpec] sub-class, used to facilitate registration
 * and discovery of supported artifact types from [ArtifactSupplier] plugins.
 */
data class SupportedArtifact<T : ArtifactSpec>(
  val name: String,
  val artifactClass: Class<T>
)
