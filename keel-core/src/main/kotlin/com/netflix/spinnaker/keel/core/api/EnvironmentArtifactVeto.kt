package com.netflix.spinnaker.keel.core.api

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import java.time.Instant

data class EnvironmentArtifactVeto(
  val targetEnvironment: String,
  val reference: String,
  val version: String,
  val vetoedBy: String?,
  val comment: String?
)

data class VetoedArtifact(
  val deliveryConfigName: String,
  val targetEnvironment: String,
  val artifact: DeliveryArtifact,
  val version: String,
  val vetoedBy: String?,
  val vetoedAt: Instant?,
  val comment: String?
)

data class EnvironmentArtifactVetoes(
  val deliveryConfigName: String,
  val targetEnvironment: String,
  val artifact: DeliveryArtifact,
  val versions: MutableSet<String>
)
