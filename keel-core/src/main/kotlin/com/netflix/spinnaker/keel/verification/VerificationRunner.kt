package com.netflix.spinnaker.keel.verification

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.plugins.VerificationEvaluator
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.api.verification.VerificationStatus
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.RUNNING
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class VerificationRunner(
  private val verificationRepository: VerificationRepository,
  private val evaluators: List<VerificationEvaluator<*>>
) {
  fun runVerificationsFor(environment: Environment, artifact: DeliveryArtifact, version: String) {
    with(VerificationContext(environment, artifact, version)) {
      val statuses = environment
        .verifyWith
        .map { it to getStatus(it) }

      if (statuses.anyAreStillRunning) {
        log.debug("Verification already running for {}", environment.name)
        return
      }

      statuses.firstOutstanding?.let { verification ->
        evaluators.evaluatorFor(verification).evaluate()
        markAsRunning(verification)
      } ?: log.debug("Verification complete for {}", environment.name)
    }
  }

  private val Collection<Pair<*, VerificationStatus?>>.anyAreStillRunning: Boolean
    get() = any { (_, status) -> status == RUNNING }

  private val Collection<Pair<Verification, VerificationStatus?>>.firstOutstanding: Verification?
    get() = firstOrNull { (_, status) -> status == null }?.first

  private fun VerificationContext.getStatus(verification: Verification) =
    verificationRepository
      .getState(verification, environment, artifact, version)
      ?.status

  private fun VerificationContext.markAsRunning(verification: Verification) {
    verificationRepository.updateState(verification, environment, artifact, version, RUNNING)
  }

  private fun List<VerificationEvaluator<*>>.evaluatorFor(verification: Verification) =
    first { it.supportedVerification.first == verification.type }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

private data class VerificationContext(
  val environment: Environment,
  val artifact: DeliveryArtifact,
  val version: String
)
