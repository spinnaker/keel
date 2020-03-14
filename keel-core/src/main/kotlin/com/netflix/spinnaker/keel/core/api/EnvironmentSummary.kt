package com.netflix.spinnaker.keel.core.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.id

/**
 * Summarized data about a specific environment, mostly for use by the UI.
 */
data class EnvironmentSummary(
  @JsonIgnore val environment: Environment,
  val artifacts: Set<ArtifactVersions>
) {
  val name: String
    get() = environment.name

  val resources: Set<String>
    get() = environment.resources.map { it.id }.toSet()

  fun hasArtifactVersion(artifact: DeliveryArtifact, version: String) =
    artifacts.any {
      (it.name == artifact.name && it.type == artifact.type) && (
        version == it.versions.current ||
          version == it.versions.deploying ||
          it.versions.previous.contains(version) ||
          it.versions.approved.contains(version) ||
          it.versions.pending.contains(version) ||
          it.versions.vetoed.contains(version)
        )
    }
}

data class ArtifactVersions(
  val name: String,
  val type: ArtifactType,
  val statuses: Set<ArtifactStatus>,
  val versions: ArtifactVersionStatus
)

data class ArtifactVersionStatus(
  val current: String?,
  val deploying: String?,
  val pending: List<String>,
  val approved: List<String>,
  val previous: List<String>,
  val vetoed: List<String>
)
