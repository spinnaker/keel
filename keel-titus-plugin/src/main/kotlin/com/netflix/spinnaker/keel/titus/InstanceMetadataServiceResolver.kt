package com.netflix.spinnaker.keel.titus

import arrow.optics.Lens
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.api.titus.TITUS_CLUSTER_V1
import com.netflix.spinnaker.keel.api.titus.TitusClusterSpec
import com.netflix.spinnaker.keel.api.titus.TitusServerGroup
import com.netflix.spinnaker.keel.api.titus.TitusServerGroupSpec
import com.netflix.spinnaker.keel.environments.DependentEnvironmentFinder
import com.netflix.spinnaker.keel.persistence.FeatureRolloutRepository
import com.netflix.spinnaker.keel.rollout.RolloutAwareResolver
import org.springframework.core.env.Environment

class InstanceMetadataServiceResolver(
  dependentEnvironmentFinder: DependentEnvironmentFinder,
  resourceToCurrentState: suspend (Resource<TitusClusterSpec>) -> Map<String, TitusServerGroup>,
  featureRolloutRepository: FeatureRolloutRepository,
  eventPublisher: EventPublisher,
  springEnvironment: Environment
) : RolloutAwareResolver<TitusClusterSpec, Map<String, TitusServerGroup>>(
  dependentEnvironmentFinder,
  resourceToCurrentState,
  featureRolloutRepository,
  eventPublisher,
  springEnvironment
) {
  override val supportedKind = TITUS_CLUSTER_V1
  override val featureName = "imdsv2"

  override fun isExplicitlySpecified(resource: Resource<TitusClusterSpec>) =
    resourceContainerAttributes.get(resource)?.containsKey("titusParameter.agent.imds.requireToken") ?: false

  override fun isAppliedTo(actualResource: Map<String, TitusServerGroup>) =
    actualResource.values.all { it.containerAttributes["titusParameter.agent.imds.requireToken"] == "true" }

  override fun activate(resource: Resource<TitusClusterSpec>) =
    resourceContainerAttributes.set(
      resource,
      (resourceContainerAttributes.get(resource) ?: emptyMap()) + ("titusParameter.agent.imds.requireToken" to "true")
    )

  override fun deactivate(resource: Resource<TitusClusterSpec>) =
    resourceContainerAttributes.set(
      resource,
      (resourceContainerAttributes.get(resource) ?: emptyMap()) - "titusParameter.agent.imds.requireToken"
    )

  override val Map<String, TitusServerGroup>.exists: Boolean
    get() = isNotEmpty()
}

val resourceSpec: Lens<Resource<TitusClusterSpec>, TitusClusterSpec> = Lens(
  get = Resource<TitusClusterSpec>::spec,
  set = { resource, spec -> resource.copy(spec = spec) }
)

val clusterSpecDefaults: Lens<TitusClusterSpec, TitusServerGroupSpec> = Lens(
  get = TitusClusterSpec::defaults,
  set = { spec, defaults -> spec.copy(_defaults = defaults) }
)

val clusterSpecMoniker: Lens<TitusClusterSpec, Moniker> = Lens(
  get = TitusClusterSpec::moniker,
  set = { spec, moniker -> spec.copy(moniker = moniker) }
)

val monikerStack: Lens<Moniker, String?> = Lens(
  get = Moniker::stack,
  set = { moniker, stack -> moniker.copy(stack = stack) }
)

val clusterSpecStack =
  clusterSpecMoniker compose monikerStack

val clusterSpecLocations: Lens<TitusClusterSpec, SimpleLocations> = Lens(
  get = TitusClusterSpec::locations,
  set = { spec, locations -> spec.copy(locations = locations) }
)

val simpleLocationsAccount: Lens<SimpleLocations, String> = Lens(
  get = SimpleLocations::account,
  set = { locations, account -> locations.copy(account = account) }
)

val clusterSpecAccount =
  clusterSpecLocations compose simpleLocationsAccount

val serverGroupSpecContainerAttributes: Lens<TitusServerGroupSpec, Map<String, String>?> = Lens(
  get = TitusServerGroupSpec::containerAttributes,
  set = { titusServerGroupSpec, containerAttributes -> titusServerGroupSpec.copy(containerAttributes = containerAttributes) }
)

/**
 * Composed lens that lets us set the deeply nested [TitusServerGroupSpec.containerAttributes] property directly on the
 * [Resource].
 */
val resourceContainerAttributes =
  resourceSpec compose clusterSpecDefaults compose serverGroupSpecContainerAttributes

val titusClusterSpecContainerAttributes =
  clusterSpecDefaults compose serverGroupSpecContainerAttributes
