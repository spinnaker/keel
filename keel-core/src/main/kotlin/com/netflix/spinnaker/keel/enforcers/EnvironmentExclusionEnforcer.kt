package com.netflix.spinnaker.keel.enforcers

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.verification.VerificationContext
import org.springframework.stereotype.Component

interface Lease {
  fun <T> withLease(action: () -> T) : T

}


@Component
class EnvironmentExclusionEnforcer(
) {
  /**
   * This lease takes the simple repository lease as well as checking that
   */
  class CompoundLease : Lease {
    override fun <T> withLease(action: () -> T): T {
      /**
       * Take simple lease
       * Check if it's actually safe
       */
      return action.invoke()
    }
  }

  fun requestVerificationLease(context: VerificationContext, verification: Verification) : Lease {
    return CompoundLease()
  }
}
