package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.api.verification.VerificationState

class SqlVerificationRepository : VerificationRepository {
  override fun getVerificationState(
    verification: Verification,
    environment: Environment,
    deliveryArtifact: DeliveryArtifact,
    version: String
  ): VerificationState? {
    TODO("Not yet implemented")
  }
}
