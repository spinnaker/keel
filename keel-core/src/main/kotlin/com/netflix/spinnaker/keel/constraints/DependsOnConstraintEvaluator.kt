package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.constraints.ConstraintState
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.FAIL
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.NOT_EVALUATED
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.OVERRIDE_FAIL
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.OVERRIDE_PASS
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.PASS
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.PENDING
import com.netflix.spinnaker.keel.api.constraints.SupportedConstraintType
import com.netflix.spinnaker.keel.api.plugins.ConstraintEvaluator
import com.netflix.spinnaker.keel.api.plugins.ConstraintEvaluator.Companion.getConstraintForEnvironment
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.api.verification.VerificationContext
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class DependsOnConstraintEvaluator(
  private val artifactRepository: ArtifactRepository,
  private val verificationRepository: VerificationRepository,
  override val eventPublisher: EventPublisher,
  private val clock: Clock
) : ConstraintEvaluator<DependsOnConstraint> {
  val CONSTRAINT_NAME = "depends-on"
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override val supportedType = SupportedConstraintType<DependsOnConstraint>("depends-on")


  override fun canPromote(
    artifact: DeliveryArtifact,
    version: String,
    deliveryConfig: DeliveryConfig,
    targetEnvironment: Environment
  ): Boolean {
    val constraint = getConstraintForEnvironment(deliveryConfig, targetEnvironment.name, supportedType.type)

    val requiredEnvironment = deliveryConfig
      .environments
      .firstOrNull { it.name == constraint.environment }
    requireNotNull(requiredEnvironment) {
      "No environment named ${constraint.environment} exists in the configuration ${deliveryConfig.name}"
    }
    return artifactRepository.wasSuccessfullyDeployedTo(
      deliveryConfig,
      artifact,
      version,
      requiredEnvironment.name
    ) && allVerificationsSucceededIn(
      deliveryConfig,
      artifact,
      version,
      requiredEnvironment
    )
  }

  override fun generateConstraintState(
    artifact: DeliveryArtifact,
    version: String,
    deliveryConfig: DeliveryConfig,
    targetEnvironment: Environment
  ): ConstraintState {
    val constraint = getConstraintForEnvironment(deliveryConfig, targetEnvironment.name, supportedType.type)

    return ConstraintState(
      deliveryConfigName = deliveryConfig.name,
      environmentName = targetEnvironment.name,
      artifactVersion = version,
      artifactReference = artifact.reference,
      type = CONSTRAINT_NAME,
      status = PASS,
      attributes = DependsOnConstraintAttributes(
        dependsOnEnvironment = constraint.environment,
      ),
      judgedAt = clock.instant(),
      judgedBy = "Spinnaker"
    )
  }

  private fun allVerificationsSucceededIn(
    deliveryConfig: DeliveryConfig,
    artifact: DeliveryArtifact,
    version: String,
    environment: Environment
  ) : Boolean {

    val context = VerificationContext(deliveryConfig, environment.name, artifact.reference, version)
    val states = verificationRepository.getStates(context)

    return environment.verifyWith
      .map { it.id }
      .all { id ->
        when (states[id]?.status) {
          PASS, OVERRIDE_PASS -> true.also {
            log.info("verification ($id) passed against version $version for app ${deliveryConfig.application}")
          }
          FAIL, OVERRIDE_FAIL -> false.also {
            log.info("verification ($id) failed against version $version for app ${deliveryConfig.application}")
          }
          NOT_EVALUATED, PENDING -> false.also {
            log.info("verification ($id) still running against version $version for app ${deliveryConfig.application}")
          }
          null -> false.also {
            log.info("no database entry for verification ($id) against version $version for app ${deliveryConfig.application}")
          }
        }
      }
  }
}
