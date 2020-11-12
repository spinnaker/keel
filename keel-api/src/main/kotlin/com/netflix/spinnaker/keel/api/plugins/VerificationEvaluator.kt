package com.netflix.spinnaker.keel.api.plugins

import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.verification.VerificationStatus

interface VerificationEvaluator<VERIFICATION: Verification> {
  val supportedVerification: Pair<String, Class<out Verification>>

  fun evaluate() : VerificationStatus

  fun start(verification: Verification)
}
