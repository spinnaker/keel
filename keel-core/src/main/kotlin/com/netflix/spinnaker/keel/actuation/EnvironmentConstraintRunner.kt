package com.netflix.spinnaker.keel.actuation

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.anyStateful
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.constraints.ConstraintEvaluator
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.PENDING
import com.netflix.spinnaker.keel.api.constraints.StatefulConstraintEvaluator
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactVetoes
import com.netflix.spinnaker.keel.core.api.PinnedEnvironment
import com.netflix.spinnaker.keel.core.comparator
import com.netflix.spinnaker.keel.persistence.KeelRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * This class is responsible for running constraints and queueing them for approval.
 * Approval into an environment is the responsibility of the [EnvironmentPromotionChecker].
 */
@Component
class EnvironmentConstraintRunner(
  private val repository: KeelRepository,
  private val constraints: List<ConstraintEvaluator<*>>,
  private val publisher: ApplicationEventPublisher
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  private val implicitConstraints: List<ConstraintEvaluator<*>> = constraints.filter { it.isImplicit() }
  private val explicitConstraints: List<ConstraintEvaluator<*>> = constraints - implicitConstraints

  // constraints that are only run if they are defined in a delivery config
  private val statefulEvaluators: List<ConstraintEvaluator<*>> = explicitConstraints
    .filterIsInstance<StatefulConstraintEvaluator<*, *>>()
  private val statelessEvaluators = explicitConstraints - statefulEvaluators

  // constraints that run for every environment in a delivery config but aren't shown to the user.
  private val implicitStatefulEvaluators: List<ConstraintEvaluator<*>> = implicitConstraints
    .filterIsInstance<StatefulConstraintEvaluator<*, *>>()
  private val implicitStatelessEvaluators: List<ConstraintEvaluator<*>> = implicitConstraints - implicitStatefulEvaluators

  /**
   * Checks the environment and determines the version that should be approved,
   * or null if there is no version that passes the constraints for an env + artifact combo.
   * Queues that version for approval if it exists.
   */
  fun checkEnvironment(
    envInfo: EnvironmentInfo
  ) {
    val pendingVersionsToCheck: MutableSet<String> =
      when (envInfo.environment.constraints.anyStateful) {
        true -> repository
          .pendingConstraintVersionsFor(envInfo.deliveryConfig.name, envInfo.environment.name)
          .filter { envInfo.versions.contains(it) }
          .toMutableSet()
        false -> mutableSetOf()
      }

    checkConstraints(
      envInfo,
      pendingVersionsToCheck
    )

    /*
     * If there are pending constraints for prior versions, that we didn't recheck in the process of
     * finding the latest version above, recheck in ascending version order
     * so they can be timed out, failed, or approved.
     */
    dealWithOldVersions(envInfo, pendingVersionsToCheck)
  }

  /**
   * Looks at all versions for an environment, and determines the latest version that passes constraints,
   * or null if none pass.
   *
   * In the process we check and evaluate constraints for each version.
   *
   * If a version passes all constraints it is queued for approval.
   */
  private fun checkConstraints(
    envInfo: EnvironmentInfo,
    pendingVersionsToCheck: MutableSet<String>
  ) {
    var version: String? = null
    var versionIsPending = false
    val vetoedVersions: Set<String> =
      (envInfo.vetoedArtifacts[envPinKey(envInfo.environment.name, envInfo.artifact)]?.versions)
        ?: emptySet()

    if (envInfo.environment.constraints.isEmpty() && implicitConstraints.isEmpty()) {
      version = envInfo.versions.firstOrNull { v ->
        !vetoedVersions.contains(v)
      }
    } else {
      version = envInfo.versions
        .firstOrNull { v ->
          pendingVersionsToCheck.remove(v) // remove to indicate we are rechecking this version
          if (vetoedVersions.contains(v)) {
            false
          } else {
            /**
             * Only check stateful evaluators if all stateless evaluators pass. We don't
             * want to request judgement or deploy a canary for artifacts that aren't
             * deployed to a required environment or outside of an allowed time.
             */
            val passesConstraints =
              checkStatelessConstraints(envInfo.artifact, envInfo.deliveryConfig, v, envInfo.environment) &&
                checkStatefulConstraints(envInfo.artifact, envInfo.deliveryConfig, v, envInfo.environment)

            if (envInfo.environment.constraints.anyStateful) {
              versionIsPending = repository
                .constraintStateFor(envInfo.deliveryConfig.name, envInfo.environment.name, v)
                .any { it.status == PENDING }
            }

            // select either the first version that passes all constraints,
            // or the first version where stateful constraints are pending.
            passesConstraints || versionIsPending
          }
        }
    }
    if (version != null && !versionIsPending) {
      // we've selected a version that passes all constraints, queue it for approval
      queueApproval(envInfo.deliveryConfig, envInfo.artifact, version, envInfo.environment.name)
    }
  }

  /**
   * Re-checks older versions with pending stateful constraints to see if they can be approved,
   * queues them for approval if they pass
   */
  private fun dealWithOldVersions(
    envInfo: EnvironmentInfo,
    pendingVersionsToCheck: MutableSet<String>
  ) {
    log.debug("pendingVersionsToCheck: [$pendingVersionsToCheck] of artifact ${envInfo.artifact.name} for environment ${envInfo.environment.name} ")
    pendingVersionsToCheck
      .sortedWith(envInfo.artifact.versioningStrategy.comparator.reversed()) // oldest first
      .forEach { version ->
        val passesConstraints =
          checkStatelessConstraints(envInfo.artifact, envInfo.deliveryConfig, version, envInfo.environment) &&
            checkStatefulConstraints(envInfo.artifact, envInfo.deliveryConfig, version, envInfo.environment)

        if (passesConstraints) {
          repository.queueAllConstraintsApproved(envInfo.deliveryConfig.name, envInfo.environment.name, version)
        }
      }
  }

  /**
   * A container to hold all info needed for these functions
   * so that we can actually break this shit up into functions
   */
  data class EnvironmentInfo(
    val deliveryConfig: DeliveryConfig,
    val environment: Environment,
    val artifact: DeliveryArtifact,
    val versions: List<String>,
    val vetoedArtifacts: Map<String, EnvironmentArtifactVetoes>
  )

  /**
   * Queues a version for approval if it's not already approved in the environment.
   * [EnvironmentPromotionChecker] handles actually approving a version for an environment.
   */
  private fun queueApproval(
    deliveryConfig: DeliveryConfig,
    artifact: DeliveryArtifact,
    version: String,
    targetEnvironment: String
  ) {
    val latestVersion = repository
      .latestVersionApprovedIn(deliveryConfig, artifact, targetEnvironment)
    if (latestVersion != version) {
      log.debug("Queueing version $version of ${artifact.type} artifact ${artifact.name} in environment $targetEnvironment for approval")
      repository.queueAllConstraintsApproved(deliveryConfig.name, targetEnvironment, version)
    }
  }

  fun checkStatelessConstraints(
    artifact: DeliveryArtifact,
    deliveryConfig: DeliveryConfig,
    version: String,
    environment: Environment
  ): Boolean =
    checkImplicitConstraints(implicitStatelessEvaluators, artifact, deliveryConfig, version, environment) &&
      checkConstraints(statelessEvaluators, artifact, deliveryConfig, version, environment)

  fun checkStatefulConstraints(
    artifact: DeliveryArtifact,
    deliveryConfig: DeliveryConfig,
    version: String,
    environment: Environment
  ): Boolean =
    checkImplicitConstraints(implicitStatefulEvaluators, artifact, deliveryConfig, version, environment) &&
      checkConstraints(statefulEvaluators, artifact, deliveryConfig, version, environment)

  /**
   * Checks constraints for a list of evaluators.
   * Evaluates the constraint for every environment passed in.
   * @return true if all constraints pass
   */
  private fun checkImplicitConstraints(
    evaluators: List<ConstraintEvaluator<*>>,
    artifact: DeliveryArtifact,
    deliveryConfig: DeliveryConfig,
    version: String,
    environment: Environment
  ): Boolean {
    return if (evaluators.isEmpty()) {
      true
    } else {
      evaluators.all { evaluator ->
        evaluator.canPromote(artifact, version, deliveryConfig, environment)
      }
    }
  }

  /**
   * Checks constraints for a list of evaluators.
   * Evaluates the constraint only if it's defined on the environment.
   * @return true if all constraints pass
   */
  private fun checkConstraints(
    evaluators: List<ConstraintEvaluator<*>>,
    artifact: DeliveryArtifact,
    deliveryConfig: DeliveryConfig,
    version: String,
    environment: Environment
  ): Boolean {
    return if (evaluators.isEmpty()) {
      true
    } else {
      evaluators.all { evaluator ->
        !environment.hasSupportedConstraint(evaluator) ||
          evaluator.canPromote(artifact, version, deliveryConfig, environment)
      }
    }
  }

  private fun Environment.hasSupportedConstraint(constraintEvaluator: ConstraintEvaluator<*>) =
    constraints.any { it.javaClass.isAssignableFrom(constraintEvaluator.supportedType.type) }

  private fun Map<String, PinnedEnvironment>.hasPinFor(
    environmentName: String,
    artifact: DeliveryArtifact
  ): Boolean {
    if (isEmpty()) {
      return false
    }

    val key = envPinKey(environmentName, artifact)
    return containsKey(key) && checkNotNull(get(key)).artifact == artifact
  }

  private fun envPinKey(environmentName: String, artifact: DeliveryArtifact): String =
    "$environmentName:${artifact.name}:${artifact.type.name.toLowerCase()}"
}
