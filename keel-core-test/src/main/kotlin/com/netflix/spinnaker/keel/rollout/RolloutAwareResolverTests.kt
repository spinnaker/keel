package com.netflix.spinnaker.keel.rollout

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.environments.DependentEnvironmentFinder
import com.netflix.spinnaker.keel.events.ResourceState
import com.netflix.spinnaker.keel.persistence.FeatureRolloutRepository
import com.netflix.spinnaker.keel.test.resource
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment
import strikt.api.Assertion
import strikt.api.expectThat

abstract class RolloutAwareResolverTests<SPEC : ResourceSpec, RESOLVED : Any, RESOLVER : RolloutAwareResolver<SPEC, RESOLVED>> {
  abstract val kind: ResourceKind
  abstract val spec: SPEC
  abstract val previousEnvironmentSpec: SPEC
  abstract val nonExistentResolvedResource: RESOLVED

  abstract fun createResolver(
    dependentEnvironmentFinder: DependentEnvironmentFinder,
    resourceToCurrentState: suspend (Resource<SPEC>) -> RESOLVED,
    featureRolloutRepository: FeatureRolloutRepository,
    eventPublisher: EventPublisher,
    springEnvironment: Environment
  ): RESOLVER

  abstract fun SPEC.withFeatureApplied(): SPEC
  abstract fun SPEC.withFeatureNotApplied(): SPEC
  abstract fun SPEC.toResolvedType(featureActive: Boolean): RESOLVED
  abstract fun Assertion.Builder<Resource<SPEC>>.featureIsApplied(): Assertion.Builder<Resource<SPEC>>
  abstract fun Assertion.Builder<Resource<SPEC>>.featureIsNotApplied(): Assertion.Builder<Resource<SPEC>>

  private val dependentEnvironmentFinder: DependentEnvironmentFinder = mockk()
  private val resourceToCurrentState: suspend (Resource<SPEC>) -> RESOLVED = mockk()
  private val featureRolloutRepository: FeatureRolloutRepository = mockk(relaxUnitFun = true) {
    coEvery { rolloutStatus(any(), any()) } returns (RolloutStatus.NOT_STARTED to 0)
  }
  private val eventPublisher: EventPublisher = mockk(relaxUnitFun = true)
  private val springEnvironment: Environment = mockk() {
    // assume the stop rollout flag is not set / default
    coEvery { getProperty("keel.rollout.imdsv2.stopOnFailure", Boolean::class.java, false) } returns false
  }

  private fun SPEC.toResource() = resource(
    kind = kind,
    spec = this
  )

  private val resolver by lazy {
    createResolver(
      dependentEnvironmentFinder,
      resourceToCurrentState,
      featureRolloutRepository,
      eventPublisher,
      springEnvironment
    )
  }

  @Test
  fun `activates the feature if not specified and there are no previous environments`() {
    val resource = spec.toResource()

    // the cluster currently uses v1
    coEvery { resourceToCurrentState(resource) } returns spec.toResolvedType(false)

    // there are no previous environments to consider
    coEvery { dependentEnvironmentFinder.resourceStatusesInDependentEnvironments(any()) } returns emptyMap()
    coEvery { dependentEnvironmentFinder.resourcesOfSameKindInDependentEnvironments(any<Resource<SPEC>>()) } returns emptyList()

    expectThat(resolver(resource)).featureIsApplied()

    verify { featureRolloutRepository.markRolloutStarted(resolver.featureName, resource.id) }
  }

  @Test
  fun `leaves setting alone if it is explicitly specified`() {
    // there are no previous environments to consider
    coEvery { dependentEnvironmentFinder.resourceStatusesInDependentEnvironments(any()) } returns emptyMap()
    coEvery { dependentEnvironmentFinder.resourcesOfSameKindInDependentEnvironments(any<Resource<SPEC>>()) } returns emptyList()

    val resource = spec.withFeatureNotApplied().toResource()

    expectThat(resolver(resource)).featureIsNotApplied()

    verify(exactly = 0) { featureRolloutRepository.markRolloutStarted(any(), any()) }
    verify { featureRolloutRepository.updateStatus(resolver.featureName, resource.id, RolloutStatus.SKIPPED) }
  }

  @Test
  fun `activates the feature if the resource is already using it`() {
    val resource = spec.toResource()

    // the cluster currently uses v2
    coEvery { resourceToCurrentState(resource) } returns spec.toResolvedType(true)

    expectThat(resolver(resource)).featureIsApplied()

    // this is not considered starting a rollout
    verify(exactly = 0) { featureRolloutRepository.markRolloutStarted(any(), any()) }

    // we take this as confirmation the rollout worked
    verify { featureRolloutRepository.updateStatus(resolver.featureName, resource.id, RolloutStatus.SUCCESSFUL) }
  }

  @Test
  fun `does not activate the feature if a previous environment is unstable`() {
    val resource = spec.toResource()

    // the cluster currently uses v2
    coEvery { resourceToCurrentState(resource) } returns spec.toResolvedType(false)

    // resources in the previous environment are not in a stable state
    coEvery {
      dependentEnvironmentFinder.resourceStatusesInDependentEnvironments(any())
    } returns listOf(previousEnvironmentSpec.toResource()).associate { it.id to ResourceState.Diff }

    expectThat(resolver(resource)).featureIsNotApplied()

    verify(exactly = 0) { featureRolloutRepository.markRolloutStarted(any(), any()) }
    verify { featureRolloutRepository.updateStatus(resolver.featureName, resource.id, RolloutStatus.NOT_STARTED) }
  }

  @Test
  fun `uses v2 if this is a new cluster regardless of the state of any preceding ones`() {
    val resource = spec.toResource()

    // this cluster doesn't even exist yet
    coEvery { resourceToCurrentState(resource) } returns nonExistentResolvedResource

    // resources in the previous environment are not in a stable state (e.g. whole app is being created)
    coEvery {
      dependentEnvironmentFinder.resourceStatusesInDependentEnvironments(any())
    } returns listOf(previousEnvironmentSpec.toResource()).associate { it.id to ResourceState.Diff }

    expectThat(resolver(resource)).featureIsApplied()

    // this isn't really a rollout, but we still want to track success in case we have to roll it back
    verify { featureRolloutRepository.markRolloutStarted(any(), any()) }
    verify { eventPublisher.publishEvent(ofType<FeatureRolloutAttempted>()) }
  }

  @Test
  fun `does not apply v2 if v2 has not been rolled out to a previous environment`() {
    val resource = spec.toResource()
    val previousEnvironmentResource = previousEnvironmentSpec.toResource()

    // the cluster currently uses v1
    coEvery { resourceToCurrentState(resource) } returns spec.toResolvedType(false)

    // the previous environment is in a stable state…
    coEvery {
      dependentEnvironmentFinder.resourceStatusesInDependentEnvironments((any()))
    } returns listOf(previousEnvironmentResource).associate { it.id to ResourceState.Ok }

    // … but its clusters are also still using v1
    coEvery {
      dependentEnvironmentFinder.resourcesOfSameKindInDependentEnvironments(any<Resource<SPEC>>())
    } returns listOf(previousEnvironmentResource)
    coEvery { resourceToCurrentState(previousEnvironmentResource) } returns previousEnvironmentSpec.toResolvedType(false)

    expectThat(resolver(resource)).featureIsNotApplied()

    verify(exactly = 0) { featureRolloutRepository.markRolloutStarted(any(), any()) }
    verify { featureRolloutRepository.updateStatus(resolver.featureName, resource.id, RolloutStatus.NOT_STARTED) }
  }

  @Test
  fun `applies v2 if v2 has successfully been rolled out to a previous environment`() {
    val resource = spec.toResource()
    val previousEnvironmentResource = previousEnvironmentSpec.toResource()

    // the cluster currently uses v1
    coEvery { resourceToCurrentState(spec.toResource()) } returns spec.toResolvedType(false)

    // the previous environment is in a stable state…
    coEvery {
      dependentEnvironmentFinder.resourceStatusesInDependentEnvironments((any()))
    } returns listOf(previousEnvironmentResource).associate { it.id to ResourceState.Ok }
    coEvery {
      dependentEnvironmentFinder.resourcesOfSameKindInDependentEnvironments(any<Resource<SPEC>>())
    } returns listOf(previousEnvironmentResource)

    // … and its clusters are already upgraded to v2
    coEvery { resourceToCurrentState(previousEnvironmentResource) } returns previousEnvironmentSpec.toResolvedType(true)

    expectThat(resolver(resource)).featureIsApplied()

    verify { featureRolloutRepository.markRolloutStarted(resolver.featureName, resource.id) }
  }

  @Test
  fun `emits an event if v2 rollout has been attempted before and seemingly not worked`() {
    val resource = spec.toResource()

    // a rollout was attempted before, but the cluster is still using v1 (e.g. failed to start with v2)
    coEvery {
      featureRolloutRepository.rolloutStatus(
        resolver.featureName,
        resource.id
      )
    } returns (RolloutStatus.IN_PROGRESS to 1)
    coEvery { resourceToCurrentState(spec.toResource()) } returns spec.toResolvedType(false)

    // there are no previous environments to consider
    coEvery { dependentEnvironmentFinder.resourceStatusesInDependentEnvironments(any()) } returns emptyMap()
    coEvery { dependentEnvironmentFinder.resourcesOfSameKindInDependentEnvironments(any<Resource<SPEC>>()) } returns emptyList()

    // the rollout is attempted again
    expectThat(resolver(resource)).featureIsApplied()

    verify { featureRolloutRepository.markRolloutStarted(resolver.featureName, resource.id) }

    // … but we also emit an event to indicate it may not be working
    verify { eventPublisher.publishEvent(FeatureRolloutFailed(resolver.featureName, resource.id)) }
  }

  @Test
  fun `stops rollout if behavior flag is set, v2 rollout has been attempted before and seemingly not worked`() {
    val resource = spec.toResource()

    // the flag indicating we should stop rollout if it appears to have failed is set
    coEvery {
      springEnvironment.getProperty(
        "keel.rollout.imdsv2.stopOnFailure",
        Boolean::class.java,
        false
      )
    } returns true

    // a rollout was attempted before, but the cluster is still using v1 (e.g. failed to start with v2)
    coEvery {
      featureRolloutRepository.rolloutStatus(
        resolver.featureName,
        resource.id
      )
    } returns (RolloutStatus.IN_PROGRESS to 1)
    coEvery { resourceToCurrentState(spec.toResource()) } returns spec.toResolvedType(false)

    // there are no previous environments to consider
    coEvery { dependentEnvironmentFinder.resourceStatusesInDependentEnvironments(any()) } returns emptyMap()
    coEvery { dependentEnvironmentFinder.resourcesOfSameKindInDependentEnvironments(any<Resource<SPEC>>()) } returns emptyList()

    // the rollout is NOT attempted again
    expectThat(resolver(resource)).featureIsNotApplied()
    verify(exactly = 0) { featureRolloutRepository.markRolloutStarted(any(), any()) }
    verify(exactly = 0) { eventPublisher.publishEvent(ofType<FeatureRolloutAttempted>()) }
    verify { featureRolloutRepository.updateStatus(resolver.featureName, resource.id, RolloutStatus.FAILED) }

    // … and we emit an event to indicate it may not be working
    verify { eventPublisher.publishEvent(FeatureRolloutFailed(resolver.featureName, resource.id)) }
  }

  @Test
  fun `applies v2 if it has been successfully applied before, but the current state has gone out of sync`() {
    val resource = spec.toResource()

    // a rollout was attempted before, but the cluster is still using v1 (e.g. failed to start with v2)
    coEvery {
      featureRolloutRepository.rolloutStatus(
        resolver.featureName,
        resource.id
      )
    } returns (RolloutStatus.SUCCESSFUL to 1)
    coEvery { resourceToCurrentState(spec.toResource()) } returns spec.toResolvedType(false)

    // we know it's safe to use V2
    expectThat(resolver(resource)).featureIsApplied()

    // this is not a new rollout so we don't update the database or trigger events
    verify(exactly = 0) { featureRolloutRepository.markRolloutStarted(any(), any()) }
    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    verify(exactly = 0) { featureRolloutRepository.updateStatus(any(), any(), any()) }
  }

  @Test
  fun `does not apply v2 if it has been unsuccessfully applied before`() {
    val resource = spec.toResource()

    // a rollout was attempted before, but the cluster is still using v1 (e.g. failed to start with v2)
    coEvery {
      featureRolloutRepository.rolloutStatus(
        resolver.featureName,
        resource.id
      )
    } returns (RolloutStatus.FAILED to 1)
    coEvery { resourceToCurrentState(spec.toResource()) } returns spec.toResolvedType(true)

    // we know it's safe to use V2
    expectThat(resolver(resource)).featureIsNotApplied()

    // this is not a new rollout so we don't update the database or trigger events
    verify(exactly = 0) { featureRolloutRepository.markRolloutStarted(any(), any()) }
    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
    verify(exactly = 0) { featureRolloutRepository.updateStatus(any(), any(), any()) }
  }
}
