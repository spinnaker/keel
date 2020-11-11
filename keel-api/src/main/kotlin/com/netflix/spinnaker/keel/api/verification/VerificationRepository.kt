package com.netflix.spinnaker.keel.api.verification

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import java.time.Instant

interface VerificationRepository {
  fun getState(
    verification: Verification,
    environment: Environment,
    deliveryArtifact: DeliveryArtifact,
    version: String
  ) : VerificationState?

  fun updateState(
    verification: Verification,
    environment: Environment,
    deliveryArtifact: DeliveryArtifact,
    version: String,
    status: VerificationStatus
  )
}

data class VerificationState(
  val status: VerificationStatus,
  val startedAt: Instant,
  val endedAt: Instant?
)

enum class VerificationStatus(val complete: Boolean) {
  RUNNING(false), PASSED(true), FAILED(true)
}
