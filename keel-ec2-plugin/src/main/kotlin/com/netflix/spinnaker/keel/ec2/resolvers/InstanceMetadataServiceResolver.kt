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
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.environments.DependentEnvironmentFinder
import com.netflix.spinnaker.keel.persistence.FeatureRolloutRepository
import com.netflix.spinnaker.keel.rollout.RolloutAwareResolver
import org.springframework.core.env.Environment

/**
 * Resolves the [LaunchConfigurationSpec.instanceMetadataServiceVersion] value if it is not explicitly specified.
 *
 * If the cluster already uses [InstanceMetadataServiceVersion.V2], or the setting has been applied to all clusters in
 * dependent environments, and those environments are stable, this resolver will select v2. Otherwise it will select v1.
 */
class InstanceMetadataServiceResolver(
  dependentEnvironmentFinder: DependentEnvironmentFinder,
  resourceToCurrentState: suspend (Resource<ClusterSpec>) -> Map<String, ServerGroup>,
  featureRolloutRepository: FeatureRolloutRepository,
  eventPublisher: EventPublisher,
  springEnvironment: Environment
) : RolloutAwareResolver<ClusterSpec, Map<String, ServerGroup>>(
  dependentEnvironmentFinder,
  resourceToCurrentState,
  featureRolloutRepository,
  eventPublisher,
  springEnvironment
) {
  override val supportedKind = EC2_CLUSTER_V1_1
  override val featureName = "imdsv2"

  override fun isAppliedTo(actualResource: Map<String, ServerGroup>) =
    actualResource.values.all { it.launchConfiguration.requireIMDSv2 }

  override fun isExplicitlySpecified(resource: Resource<ClusterSpec>) =
    resourceInstanceMetadataServiceVersion.get(resource) != null

  override fun activate(resource: Resource<ClusterSpec>) =
    resourceInstanceMetadataServiceVersion.set(resource, V2)

  override fun deactivate(resource: Resource<ClusterSpec>) =
    resourceInstanceMetadataServiceVersion.set(resource, V1)

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
}
