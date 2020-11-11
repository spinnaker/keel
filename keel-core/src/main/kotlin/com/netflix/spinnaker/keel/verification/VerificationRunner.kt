package com.netflix.spinnaker.keel.verification

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.plugins.VerificationEvaluator
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.RUNNING
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class VerificationRunner(
  private val verificationRepository: VerificationRepository,
  private val evaluators: List<VerificationEvaluator<*>>
) {
  fun runVerificationsFor(environment: Environment, artifact: DeliveryArtifact, version: String) {
    val statuses = environment
      .verifyWith
      .map {
        it to verificationRepository
          .getState(it, environment, artifact, version)
          ?.status
      }

    if (statuses.any { (_, status) -> status == RUNNING }) {
      log.debug("Verification already running for {}", environment.name)
      return
    }

    val verificationToRun = statuses.firstOrNull { (_, status) -> status == null }?.first
    if (verificationToRun != null) {
      val evaluator = evaluators.first {
        it.supportedVerification.first == verificationToRun.type
      }
      evaluator.evaluate()

      verificationRepository.updateState(verificationToRun, environment, artifact, version, RUNNING)
    } else {
      log.debug("Verification complete for {}", environment.name)
    }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
