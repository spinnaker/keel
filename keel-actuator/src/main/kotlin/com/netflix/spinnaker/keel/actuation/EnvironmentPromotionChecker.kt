package com.netflix.spinnaker.keel.actuation

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.constraints.ConstraintEvaluator
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.telemetry.ArtifactVersionApproved
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class EnvironmentPromotionChecker(
  private val artifactRepository: ArtifactRepository,
  private val constraints: List<ConstraintEvaluator<*>>,
  private val publisher: ApplicationEventPublisher
) {

  suspend fun checkEnvironments(deliveryConfig: DeliveryConfig) {
    deliveryConfig
      .artifacts
      .associateWith { artifactRepository.versions(it) }
      .forEach { (artifact, versions) ->
        if (versions.isEmpty()) {
          log.warn("No versions for ${artifact.type} artifact ${artifact.name} are known")
        } else {
          deliveryConfig.environments.forEach { environment ->
            val version = if (environment.constraints.isEmpty()) {
              versions.firstOrNull()
            } else {
              versions.firstOrNull { v ->
                constraints.all { constraintEvaluator ->
                  !environment.hasSupportedConstraint(constraintEvaluator) || constraintEvaluator.canPromote(artifact, v, deliveryConfig, environment.name)
                }
              }
            }

            if (version == null) {
              log.warn("No version of {} passes constraints for environment {}", artifact.name, environment.name)
            } else {
              val isNewVersion = artifactRepository
                .approveVersionFor(deliveryConfig, artifact, version, environment.name)
              if (isNewVersion) {
                log.info(
                  "Approved {} {} version {} for {} environment {} in {}",
                  artifact.name,
                  artifact.type,
                  version,
                  deliveryConfig.name,
                  environment.name,
                  deliveryConfig.application
                )
                publisher.publishEvent(
                  ArtifactVersionApproved(
                    deliveryConfig.application,
                    deliveryConfig.name,
                    environment.name,
                    artifact.name,
                    artifact.type,
                    version
                  )
                )
              }
            }
          }
        }
      }
  }

  private fun Environment.hasSupportedConstraint(constraintEvaluator: ConstraintEvaluator<*>) =
    constraints.any { it.javaClass.isAssignableFrom(constraintEvaluator.constraintType) }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
