package com.netflix.spinnaker.keel.core.api

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.core.validateComment
import java.time.Instant

/**
 * The request body of an artifact veto
 */
data class EnvironmentArtifactVeto(
  val targetEnvironment: String,
  val reference: String,
  val version: String,
  val vetoedBy: String?,
  val comment: String?
) {
  init {
    validateComment(comment)
  }
}

data class ArtifactVersionVetoData(
  val version: String,
  val vetoedAt: Instant?,
  val vetoedBy: String?,
  val comment: String?,
)

data class EnvironmentArtifactVetoes(
  val deliveryConfigName: String,
  val targetEnvironment: String,
  val artifact: DeliveryArtifact,
  val versions: MutableSet<ArtifactVersionVetoData>
)
