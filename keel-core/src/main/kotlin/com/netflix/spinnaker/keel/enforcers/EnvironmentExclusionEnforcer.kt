package com.netflix.spinnaker.keel.enforcers

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.verification.VerificationContext
import org.springframework.stereotype.Component

/**
 * Exception thrown when it's not safe to take action against the environment because
 * something is is acting on it
 */
class EnvironmentCurrentlyBeingActedOn(message: String) : Exception(message) { }

@Component
class EnvironmentExclusionEnforcer {
  /**
   * To get a verification lease against an environment, need:
   *
   * 1. An environment lease
   * 2. No active deployments
   * 3. No active verifications
   */
  fun <T> withVerificationLease(context: VerificationContext, action: () -> T) : T {
    val environment = context.environment

    ensureNoActiveDeployments(environment)
    ensureNoActiveVerifications(environment)

    return action.invoke()
  }

  /**
   * To get an actuation lease against an environment, need:
   *
   * 1. An environment lease
   * 2. No active verifications
   *
   * It's ok if other actuations (e.g., deployments) are going on.
   */
  suspend fun <T> withActuationLease(environment: Environment, action: suspend () -> T) : T {
    ensureNoActiveVerifications(environment)
    return action.invoke()
  }


  /**
   * @throws EnvironmentCurrentlyBeingActedOn if there's an active deployment
   */
  private fun ensureNoActiveDeployments(environment: Environment) {
    /**
     * To be implemented in a future PR.
     */
  }

  /**
   * @throws EnvironmentCurrentlyBeingActedOn if there's an active verification
   */
  private fun ensureNoActiveVerifications(environment: Environment) {
    /**
     * To be implemented in a future PR
     */
  }
}
