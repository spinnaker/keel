package com.netflix.spinnaker.keel.api.verification

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import java.time.Duration
import java.time.Instant

interface VerificationRepository {
  /**
   * @return the current state of [verification] as run against [context], or `null` if it has not
   * yet been run.
   */
  fun getState(
    context: VerificationContext,
    verification: Verification
  ): VerificationState?

  /**
   * @return a map of the current states of all verifications as run against [context]. The key of the map
   * is the id of the Verification object
   */
  fun getStates(
    context: VerificationContext,
  ) : Map<String, VerificationState>

  /**
   * Updates the state of [verification] as run against [context].
   */
  fun updateState(
    context: VerificationContext,
    verification: Verification,
    status: VerificationStatus
  )

  fun nextEnvironmentsForVerification(minTimeSinceLastCheck: Duration, limit: Int) : Collection<VerificationContext>
}

data class VerificationState(
  val status: VerificationStatus,
  val startedAt: Instant,
  val endedAt: Instant?
)

enum class VerificationStatus(val complete: Boolean) {
  RUNNING(false), PASSED(true), FAILED(true)
}

data class VerificationContext(
  val deliveryConfig: DeliveryConfig,
  val environmentName: String,
  val artifactReference: String,
  val version: String
) {
  val environment: Environment =
    deliveryConfig.environments.first { it.name == environmentName }

  val artifact: DeliveryArtifact =
    deliveryConfig.artifacts.first { it.reference == artifactReference }

  val verifications: Collection<Verification> = environment.verifyWith
}
