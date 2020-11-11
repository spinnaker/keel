package com.netflix.spinnaker.keel.api.plugins

import com.netflix.spinnaker.keel.api.Verification

interface VerificationEvaluator<VERIFICATION: Verification> {
  val supportedVerification: Pair<String, Class<out Verification>>

  fun evaluate()
}
