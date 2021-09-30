package com.netflix.spinnaker.keel.rollout

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.environments.DependentEnvironmentFinder
import com.netflix.spinnaker.keel.events.ResourceState.Ok
import com.netflix.spinnaker.keel.persistence.FeatureRolloutRepository
import com.netflix.spinnaker.keel.persistence.markRolloutStarted
import com.netflix.spinnaker.keel.rollout.RolloutStatus.FAILED
import com.netflix.spinnaker.keel.rollout.RolloutStatus.NOT_STARTED
import com.netflix.spinnaker.keel.rollout.RolloutStatus.SKIPPED
import com.netflix.spinnaker.keel.rollout.RolloutStatus.SUCCESSFUL
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Base class for [Resolver] implementations that are used to safely roll out features to each environment in an
 * application in turn.
 *
 * If a feature is explicitly set (in any state) in the spec, the resolver will be a no-op. Otherwise it will activate
 * the feature for a given resource if:
 * - it is currently active, or all of the following apply:
 *   - all resources of the same type in previous environments have the feature activated.
 *   - all resources in previous environments are "healthy".
 *   - the rollout of the feature was not attempted before for the same resource.
 */
abstract class RolloutAwareResolver<SPEC : ResourceSpec, RESOLVED : Any>(
  private val dependentEnvironmentFinder: DependentEnvironmentFinder,
  private val resourceToCurrentState: suspend (Resource<SPEC>) -> RESOLVED,
  private val featureRolloutRepository: FeatureRolloutRepository,
  private val eventPublisher: EventPublisher
) : Resolver<SPEC> {

  /**
   * The name of the feature this resolver deals with.
   */
  abstract val featureName: String

  /**
   * @return `true` if the feature is explicitly set (whether enabled or disabled) in [resource], `false` if not (and
   * therefore this resolver needs to make a decision about it).
   */
  abstract fun isExplicitlySpecified(resource: Resource<SPEC>): Boolean

  /**
   * @return `true` if the feature is enabled on [actualResource], `false` if not.
   */
  abstract fun isAppliedTo(actualResource: RESOLVED): Boolean // TODO: do we need a "partially applied" state for things like multi-region resources?

  /**
   * @return a copy of [resource] with the feature activated.
   */
  abstract fun activate(resource: Resource<SPEC>): Resource<SPEC>

  /**
   * @return a copy of [resource] with the feature deactivated.
   */
  abstract fun deactivate(resource: Resource<SPEC>): Resource<SPEC>

  /**
   * `true` if the state of this resolved resource indicates that it exists, `false` if it's a new resource that has not
   * been created yet.
   */
  abstract val RESOLVED.exists: Boolean

  override fun invoke(resource: Resource<SPEC>): Resource<SPEC> {
    val currentState by lazy {
      runBlocking(IO) {
        resourceToCurrentState(resource)
      }
    }

    val (status, attemptCount) = featureRolloutRepository.rolloutStatus(featureName, resource.id)

    return when {
      isExplicitlySpecified(resource) -> {
        log.debug("{} explicitly specifies {}", resource.id, featureName)
        featureRolloutRepository.updateStatus(featureName, resource.id, SKIPPED)
        resource
      }
      status == SUCCESSFUL -> {
        log.debug("{} was already successfully rolled out to {}", featureName, resource.id)
        activate(resource)
      }
      status == FAILED -> {
        log.debug("rollout of {} to {} was unsuccessful previously", featureName, resource.id)
        deactivate(resource)
      }
      isNewResource(currentState) -> {
        log.debug("{} is a new resource, so applying {} right away", resource.id, featureName)
        featureRolloutRepository.markRolloutStarted(featureName, resource.id)
        eventPublisher.publishEvent(FeatureRolloutAttempted(featureName, resource))
        activate(resource)
      }
      isAlreadyRolledOutToThisResource(currentState) -> {
        log.debug("{} is already using {}", resource.id, featureName)
        featureRolloutRepository.updateStatus(featureName, resource.id, SUCCESSFUL)
        activate(resource)
      }
      !previousEnvironmentsStable(resource) -> {
        log.debug(
          "dependent environments for {} are not currently stable, not rolling out {}",
          resource.id,
          featureName
        )
        featureRolloutRepository.updateStatus(featureName, resource.id, NOT_STARTED)
        deactivate(resource)
      }
      !isRolledOutToPreviousEnvironments(resource) -> {
        log.debug("{} is not yet rolled out to dependent environments for {}", featureName, resource.id)
        featureRolloutRepository.updateStatus(featureName, resource.id, NOT_STARTED)
        deactivate(resource)
      }
      attemptCount > 0 -> {
        log.warn("{} rollout has been attempted before for {} and appears to have failed", featureName, resource.id)
        eventPublisher.publishEvent(FeatureRolloutFailed(featureName, resource))
        featureRolloutRepository.updateStatus(featureName, resource.id, FAILED)
        deactivate(resource)
      }
      else -> {
        log.debug("applying {} to {}", featureName, resource.id)
        featureRolloutRepository.markRolloutStarted(featureName, resource)
        eventPublisher.publishEvent(FeatureRolloutAttempted(featureName, resource))
        activate(resource)
      }
    }
  }

  private fun isAlreadyRolledOutToThisResource(currentState: RESOLVED): Boolean =
    isAppliedTo(currentState)

  private fun isNewResource(currentState: RESOLVED): Boolean =
    !currentState.exists

  private fun isRolledOutToPreviousEnvironments(resource: Resource<SPEC>): Boolean =
    runBlocking(IO) {
      dependentEnvironmentFinder.resourcesOfSameKindInDependentEnvironments(resource)
        .map { async { resourceToCurrentState(it) } }
        .awaitAll()
        .all { isAppliedTo(it) }
    }

  private fun previousEnvironmentsStable(resource: Resource<SPEC>): Boolean {
    val dependentEnvironmentResourceStatuses =
      dependentEnvironmentFinder.resourceStatusesInDependentEnvironments(resource)
    return dependentEnvironmentResourceStatuses.values.all { it == Ok }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
