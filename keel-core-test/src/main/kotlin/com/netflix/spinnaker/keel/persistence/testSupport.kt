package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.artifacts.ArtifactInstance
import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import java.time.Instant

fun ArtifactSpec.toArtifactInstance(version: String, status: ArtifactStatus? = null, createdAt: Instant? = null) =
  ArtifactInstance(
    name = name,
    type = type,
    reference = reference,
    version = version,
    metadata = mapOf(
      "releaseStatus" to status,
      "createdAt" to createdAt
    )
  ).normalized()

