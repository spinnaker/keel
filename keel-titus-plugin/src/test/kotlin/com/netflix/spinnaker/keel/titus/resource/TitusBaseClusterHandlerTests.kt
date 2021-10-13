package com.netflix.spinnaker.keel.titus.resource

import com.netflix.spinnaker.keel.api.Highlander
import com.netflix.spinnaker.keel.api.ManagedRolloutConfig
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.RedBlack
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceDiff
import com.netflix.spinnaker.keel.api.SelectionStrategy
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.SimpleRegionSpec
import com.netflix.spinnaker.keel.api.StaggeredRegion
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.ec2.Capacity
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec
import com.netflix.spinnaker.keel.api.plugins.BaseClusterHandlerTests
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.api.titus.TITUS_CLUSTER_V1
import com.netflix.spinnaker.keel.api.titus.TitusClusterSpec
import com.netflix.spinnaker.keel.api.titus.TitusServerGroup
import com.netflix.spinnaker.keel.api.titus.TitusServerGroupSpec
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.Credential
import com.netflix.spinnaker.keel.clouddriver.model.DockerImage
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.docker.DigestProvider
import com.netflix.spinnaker.keel.orca.ClusterExportHelper
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.titus.TitusClusterHandler
import com.netflix.spinnaker.keel.titus.byRegion
import com.netflix.spinnaker.keel.titus.resolve
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.time.Clock
import java.time.Duration

class TitusBaseClusterHandlerTests : BaseClusterHandlerTests<TitusClusterSpec, TitusServerGroup, TitusClusterHandler>() {
  val cloudDriverService: CloudDriverService = mockk(relaxed = true) {
   coEvery { findDockerImages(any(),any(),any()) } returns listOf(
     DockerImage(
       account = "account", repository = "repo", tag = "butter", digest = "1234567890"
     )
   )
  }
  val cloudDriverCache: CloudDriverCache = mockk() {
    every { credentialBy("account") } returns Credential(
      name = "account",
      type = "titus",
      environment = "testenv",
      attributes = mutableMapOf("awsAccount" to "awsAccount", "registry" to "testregistry")
    )
  }
  private val orcaService: OrcaService = mockk()
  private val clusterExportHelper: ClusterExportHelper = mockk()

  val metadata = mapOf("id" to "1234", "application" to "waffles", "serviceAccount" to "me@you.com" )

  val baseSpec = TitusClusterSpec(
    moniker = Moniker("waffles"),
    locations = SimpleLocations(
      account = "account",
      regions = setOf(SimpleRegionSpec("east"))
    ),
    container = DigestProvider(organization = "waffles", image = "butter", digest = "1234567890"),
    _defaults = TitusServerGroupSpec(
      capacity = ClusterSpec.CapacitySpec(1, 4, 2)
    ),
    managedRollout = ManagedRolloutConfig(enabled = false),
    deployWith = Highlander()
  )

  override fun getRegions(resource: Resource<TitusClusterSpec>): List<String> =
    resource.spec.locations.regions.map { it.name }.toList()

  override fun createSpyHandler(
    resolvers: List<Resolver<*>>,
    clock: Clock,
    eventPublisher: EventPublisher,
    taskLauncher: TaskLauncher,
  ): TitusClusterHandler =
    spyk(TitusClusterHandler(
      cloudDriverService = cloudDriverService,
      cloudDriverCache = cloudDriverCache,
      orcaService = orcaService,
      clock = clock,
      taskLauncher = taskLauncher,
      eventPublisher = eventPublisher,
      resolvers = resolvers,
      clusterExportHelper = clusterExportHelper
    ))

  override fun getSingleRegionCluster(): Resource<TitusClusterSpec> {
    return Resource(
      kind = TITUS_CLUSTER_V1.kind,
      metadata = metadata,
      spec = baseSpec
    )
  }

  override fun getMultiRegionCluster(): Resource<TitusClusterSpec> {
    val spec = baseSpec.copy(
      locations = SimpleLocations(
        account = "account",
        regions = setOf(SimpleRegionSpec("east"), SimpleRegionSpec("west"))
      )
    )
    return Resource(
      kind = TITUS_CLUSTER_V1.kind,
      metadata = metadata,
      spec = spec
    )
  }

  override fun getMultiRegionStaggeredDeployCluster(): Resource<TitusClusterSpec> {
    val spec = baseSpec.copy(
      locations = SimpleLocations(
        account = "account",
        regions = setOf(SimpleRegionSpec("east"), SimpleRegionSpec("west"))
      ),
      deployWith = RedBlack(
        stagger = listOf(
          StaggeredRegion(
            region = "east",
            pauseTime = Duration.ofMinutes(1)
          )
        )
      )
    )
    return Resource(
      kind = TITUS_CLUSTER_V1.kind,
      metadata = metadata,
      spec = spec
    )
  }

  override fun getMultiRegionManagedRolloutCluster(): Resource<TitusClusterSpec> {
    val spec = baseSpec.copy(
      locations = SimpleLocations(
        account = "account",
        regions = setOf(SimpleRegionSpec("east"), SimpleRegionSpec("west"))
      ),
      managedRollout = ManagedRolloutConfig(enabled = true, selectionStrategy = SelectionStrategy.ALPHABETICAL)
    )
    return Resource(
      kind = TITUS_CLUSTER_V1.kind,
      metadata = metadata,
      spec = spec
    )
  }

  override fun getDiffInMoreThanEnabled(resource: Resource<TitusClusterSpec>): ResourceDiff<Map<String, TitusServerGroup>> {
    val currentServerGroups = resource.spec.resolve()
      .byRegion()
    val desiredServerGroups = resource.spec.resolve()
      .map { it.withDoubleCapacity().withManyEnabled() }.byRegion()
    return DefaultResourceDiff(desiredServerGroups, currentServerGroups)
  }

  override fun getDiffOnlyInEnabled(resource: Resource<TitusClusterSpec>): ResourceDiff<Map<String, TitusServerGroup>> {
    val currentServerGroups = resource.spec.resolve()
      .byRegion()
    val desiredServerGroups = resource.spec.resolve()
      .map { it.withManyEnabled() }.byRegion()
    return DefaultResourceDiff(desiredServerGroups, currentServerGroups)
  }

  override fun getDiffInCapacity(resource: Resource<TitusClusterSpec>): ResourceDiff<Map<String, TitusServerGroup>> {
    val current = resource.spec.resolve().byRegion()
    val desired = resource.spec.resolve().map { it.withDoubleCapacity() }.byRegion()
    return DefaultResourceDiff(desired, current)
  }

  override fun getDiffInImage(resource: Resource<TitusClusterSpec>): ResourceDiff<Map<String, TitusServerGroup>> {
    val current = resource.spec.resolve().byRegion()
    val desired = resource.spec.resolve().map { it.withADifferentImage() }.byRegion()
    return DefaultResourceDiff(desired, current)
  }

  override fun getCreateAndModifyDiff(resource: Resource<TitusClusterSpec>): ResourceDiff<Map<String, TitusServerGroup>> {
    val current = resource.spec.resolve().byRegion()
    val desired = resource.spec.resolve().map {
      when(it.location.region) {
        "east" -> it.withADifferentImage()
        else -> it.withDoubleCapacity()
      }
    }.byRegion()
    return DefaultResourceDiff(desired, current)
  }

  private fun TitusServerGroup.withDoubleCapacity(): TitusServerGroup =
    copy(
      capacity = Capacity.DefaultCapacity(
        min = capacity.min * 2,
        max = capacity.max * 2,
        desired = capacity.desired * 2
      )
    )

  private fun TitusServerGroup.withManyEnabled(): TitusServerGroup =
    copy(
      onlyEnabledServerGroup = false
    )

  private fun TitusServerGroup.withADifferentImage(): TitusServerGroup =
    copy(
      container = DigestProvider(organization = "waffles", image = "syrup", digest = "1255555555555555"),
    )
}
