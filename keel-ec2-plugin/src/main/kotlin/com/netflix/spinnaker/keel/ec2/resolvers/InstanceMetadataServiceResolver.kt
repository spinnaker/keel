package com.netflix.spinnaker.keel.ec2.resolvers

import arrow.optics.Lens
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec.ServerGroupSpec
import com.netflix.spinnaker.keel.api.ec2.EC2_CLUSTER_V1_1
import com.netflix.spinnaker.keel.api.ec2.InstanceMetadataServiceVersion
import com.netflix.spinnaker.keel.api.ec2.InstanceMetadataServiceVersion.V1
import com.netflix.spinnaker.keel.api.ec2.InstanceMetadataServiceVersion.V2
import com.netflix.spinnaker.keel.api.ec2.LaunchConfigurationSpec
import com.netflix.spinnaker.keel.api.ec2.ServerGroup
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.environments.DependentEnvironmentFinder
import com.netflix.spinnaker.keel.events.ResourceState.Ok
import com.netflix.spinnaker.keel.persistence.FeatureRolloutRepository
import com.netflix.spinnaker.keel.persistence.countRolloutAttempts
import com.netflix.spinnaker.keel.persistence.markRolloutStarted
import com.netflix.spinnaker.keel.rollout.FeatureRolloutAttempted
import com.netflix.spinnaker.keel.rollout.FeatureRolloutFailed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment

/**
 * Resolves the [LaunchConfigurationSpec.instanceMetadataServiceVersion] value if it is not explicitly specified.
 *
 * If the cluster already uses [InstanceMetadataServiceVersion.V2], or the setting has been applied to all clusters in
 * dependent environments, and those environments are stable, this resolver will select v2. Otherwise it will select v1.
 */
class InstanceMetadataServiceResolver(
  private val dependentEnvironmentFinder: DependentEnvironmentFinder,
  private val resourceToCurrentState: suspend (Resource<ClusterSpec>) -> Map<String, ServerGroup>,
  private val featureRolloutRepository: FeatureRolloutRepository,
  private val eventPublisher: EventPublisher,
  private val springEnvironment: Environment
) : Resolver<ClusterSpec> {
  companion object {
    const val featureName = "imdsv2"
  }

  override val supportedKind = EC2_CLUSTER_V1_1

  private val resourceSpec: Lens<Resource<ClusterSpec>, ClusterSpec> = Lens(
    get = Resource<ClusterSpec>::spec,
    set = { resource, spec -> resource.copy(spec = spec) }
  )

  private val clusterSpecDefaults: Lens<ClusterSpec, ServerGroupSpec> = Lens(
    get = ClusterSpec::defaults,
    set = { spec, defaults -> spec.copy(_defaults = defaults) }
  )

  private val serverGroupSpecLaunchConfigurationSpec: Lens<ServerGroupSpec, LaunchConfigurationSpec?> = Lens(
    get = ServerGroupSpec::launchConfiguration,
    set = { serverGroupSpec, launchConfigurationSpec -> serverGroupSpec.copy(launchConfiguration = launchConfigurationSpec) }
  )

  private val launchConfigurationSpecInstanceMetadataServiceVersion: Lens<LaunchConfigurationSpec?, InstanceMetadataServiceVersion?> =
    Lens(
      get = { it?.instanceMetadataServiceVersion },
      set = { launchConfigurationSpec, instanceMetadataServiceVersion ->
        launchConfigurationSpec?.copy(
          instanceMetadataServiceVersion = instanceMetadataServiceVersion
        ) ?: LaunchConfigurationSpec(instanceMetadataServiceVersion = instanceMetadataServiceVersion)
      }
    )

  /**
   * Composed lens that lets us set the deeply nested [LaunchConfigurationSpec.instanceMetadataServiceVersion] property
   * directly on the [Resource].
   */
  private val resourceInstanceMetadataServiceVersion =
    resourceSpec compose clusterSpecDefaults compose serverGroupSpecLaunchConfigurationSpec compose launchConfigurationSpecInstanceMetadataServiceVersion

  override fun invoke(resource: Resource<ClusterSpec>): Resource<ClusterSpec> =
    when (val specifiedVersion = resourceInstanceMetadataServiceVersion.get(resource)) {
      null -> {
        if (isAlreadyRolledOutToThisCluster(resource)) {
          log.debug("cluster {} is already using IMDSv2", resource.id)
          resourceInstanceMetadataServiceVersion.set(resource, V2)
        } else if (!previousEnvironmentsStable(resource)) {
          log.debug("dependent environments for {} are not currently stable, not rolling out IMDSv2", resource.id)
          resourceInstanceMetadataServiceVersion.set(resource, V1)
        } else if (!isRolledOutToPreviousEnvironments(resource)) {
          log.debug("IMDSv2 is not yet rolled out to dependent environments for {}", resource.id)
          resourceInstanceMetadataServiceVersion.set(resource, V1)
        } else {
          val wasTriedBefore = featureRolloutRepository.countRolloutAttempts(featureName, resource) > 0

          if (wasTriedBefore) {
            log.warn("IMDSv2 rollout has been attempted before for {} and may have failed", resource.id)
            eventPublisher.publishEvent(FeatureRolloutFailed(featureName, resource))
          }

          if (wasTriedBefore && stopRolloutOnApparentFailure) {
            log.warn("not applying IMDSv2 to {}", resource.id)
            resourceInstanceMetadataServiceVersion.set(resource, V1)
          } else {
            log.debug("applying IMDSv2 to {}", resource.id)
            featureRolloutRepository.markRolloutStarted(featureName, resource)
            eventPublisher.publishEvent(FeatureRolloutAttempted(featureName, resource))
            resourceInstanceMetadataServiceVersion.set(resource, V2)
          }
        }
      }
      else -> {
        log.debug("{} explicitly specifies IMDS {}", resource.id, specifiedVersion)
        resource
      }
    }

  private fun isAlreadyRolledOutToThisCluster(resource: Resource<ClusterSpec>): Boolean =
    runBlocking(Dispatchers.IO) {
      resourceToCurrentState(resource)
        .values
        .all { it.launchConfiguration.requireIMDSv2 }
    }

  private fun isRolledOutToPreviousEnvironments(resource: Resource<ClusterSpec>): Boolean =
    runBlocking(Dispatchers.IO) {
      dependentEnvironmentFinder.resourcesOfSameKindInDependentEnvironments(resource)
        .map { async { resourceToCurrentState(it) } }
        .awaitAll()
        .flatMap { it.values }
        .all { it.launchConfiguration.requireIMDSv2 }
    }

  private fun previousEnvironmentsStable(resource: Resource<ClusterSpec>): Boolean {
    val dependentEnvironmentResourceStatuses =
      dependentEnvironmentFinder.resourceStatusesInDependentEnvironments(resource)
    return dependentEnvironmentResourceStatuses.values.all { it == Ok }
  }

  private val stopRolloutOnApparentFailure: Boolean
    get() = springEnvironment.getProperty("keel.rollout.imdsv2.stopOnFailure", Boolean::class.java, false)

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
