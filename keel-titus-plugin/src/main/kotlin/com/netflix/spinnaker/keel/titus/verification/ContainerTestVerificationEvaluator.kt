package com.netflix.spinnaker.keel.titus.verification

import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.plugins.VerificationEvaluator
import com.netflix.spinnaker.keel.api.verification.VerificationStatus
import org.springframework.stereotype.Component

@Component
class ContainerTestVerificationEvaluator : VerificationEvaluator<ContainerTestVerification> {
  override val supportedVerification: Pair<String, Class<ContainerTestVerification>> =
    "container-tests" to ContainerTestVerification::class.java

  override fun evaluate(): VerificationStatus {
    TODO("Not yet implemented")
  }

  override fun start(verification: Verification) {
    TODO("Not yet implemented")
  }
}
