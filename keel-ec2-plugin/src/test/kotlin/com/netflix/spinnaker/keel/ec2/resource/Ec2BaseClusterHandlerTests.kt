package com.netflix.spinnaker.keel.ec2.resource

import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.RedBlack
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceDiff
import com.netflix.spinnaker.keel.api.StaggeredRegion
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.SubnetAwareRegionSpec
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.ec2.Capacity
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec
import com.netflix.spinnaker.keel.api.ec2.EC2_CLUSTER_V1_1
import com.netflix.spinnaker.keel.api.ec2.LaunchConfigurationSpec
import com.netflix.spinnaker.keel.api.ec2.ServerGroup
import com.netflix.spinnaker.keel.api.ec2.VirtualMachineImage
import com.netflix.spinnaker.keel.api.ec2.byRegion
import com.netflix.spinnaker.keel.api.ec2.resolve
import com.netflix.spinnaker.keel.api.plugins.BaseClusterHandlerTests
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.igor.artifact.ArtifactService
import com.netflix.spinnaker.keel.orca.ClusterExportHelper
import com.netflix.spinnaker.keel.orca.OrcaService
import com.nhaarman.mockitokotlin2.any
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.Duration

class Ec2BaseClusterHandlerTests : BaseClusterHandlerTests<ClusterSpec, ServerGroup, ClusterHandler>() {
  private val cloudDriverService: CloudDriverService = mockk()
  private val cloudDriverCache: CloudDriverCache = mockk()
  private val orcaService: OrcaService = mockk()
  private val clusterExportHelper: ClusterExportHelper = mockk()
  private val springEnv: Environment = mockk(relaxed = true)
  private val blockDeviceConfig : BlockDeviceConfig = BlockDeviceConfig(springEnv, VolumeDefaultConfiguration())
  val artifactService = mockk<ArtifactService>()

  val metadata = mapOf("id" to "1234", "application" to "waffles", "serviceAccount" to "me@you.com" )

  val launchConfigurationSpec = LaunchConfigurationSpec(
    image = VirtualMachineImage("id-1", "my-app-1.2.3", "base-1"),
    instanceType = "m3.xl",
    keyPair = "keypair",
    ebsOptimized = false,
    instanceMonitoring = false,
    ramdiskId = "1"
  )

  val baseSpec = ClusterSpec(
    moniker = Moniker("waffles"),
    artifactReference = "my-artfact",
    locations = SubnetAwareLocations(
      account = "account",
      regions = setOf(SubnetAwareRegionSpec("east")),
      subnet = "subnet-1"
    ),
    _defaults = ClusterSpec.ServerGroupSpec(
      launchConfiguration = launchConfigurationSpec
    )
  )

  override fun createSpyHandler(
    resolvers: List<Resolver<*>>,
    clock: Clock,
    eventPublisher: EventPublisher,
    taskLauncher: TaskLauncher,
  ): ClusterHandler =
    spyk(ClusterHandler(
      cloudDriverService = cloudDriverService,
      cloudDriverCache = cloudDriverCache,
      orcaService = orcaService,
      clock = clock,
      taskLauncher = taskLauncher,
      eventPublisher = eventPublisher,
      resolvers = resolvers,
      clusterExportHelper = clusterExportHelper,
      blockDeviceConfig = blockDeviceConfig,
      artifactService = artifactService
    ))

  override fun getRegions(resource: Resource<ClusterSpec>): List<String> =
    resource.spec.locations.regions.map { it.name }.toList()

  override fun getSingleRegionCluster(): Resource<ClusterSpec> {
    return Resource(
      kind = EC2_CLUSTER_V1_1.kind,
      metadata = metadata,
      spec = baseSpec
    )
  }

  override fun getMultiRegionCluster(): Resource<ClusterSpec> {
    val spec = baseSpec.copy(
      locations = SubnetAwareLocations(
        account = "account",
        regions = setOf(SubnetAwareRegionSpec("east"), SubnetAwareRegionSpec("west")),
        subnet = "subnet-1"
      )
    )
    return Resource(
      kind = EC2_CLUSTER_V1_1.kind,
      metadata = metadata,
      spec = spec
    )
  }

  override fun getMultiRegionStaggeredDeployCluster(): Resource<ClusterSpec> {
    val spec = baseSpec.copy(
      locations = SubnetAwareLocations(
        account = "account",
        regions = setOf(SubnetAwareRegionSpec("east"), SubnetAwareRegionSpec("west")),
        subnet = "subnet-1"
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
      kind = EC2_CLUSTER_V1_1.kind,
      metadata = metadata,
      spec = spec
    )
  }

  override fun getDiffInMoreThanEnabled(resource: Resource<ClusterSpec>): ResourceDiff<Map<String, ServerGroup>> {
    val currentServerGroups = resource.spec.resolve()
      .byRegion()
    val desiredServerGroups = resource.spec.resolve()
      .map { it.withDoubleCapacity().withManyEnabled() }.byRegion()
    return DefaultResourceDiff(desiredServerGroups, currentServerGroups)
  }

  override fun getDiffOnlyInEnabled(resource: Resource<ClusterSpec>): ResourceDiff<Map<String, ServerGroup>> {
    val currentServerGroups = resource.spec.resolve()
      .byRegion()
    val desiredServerGroups = resource.spec.resolve()
      .map { it.withManyEnabled() }.byRegion()
    return DefaultResourceDiff(desiredServerGroups, currentServerGroups)
  }

  override fun getDiffInCapacity(resource: Resource<ClusterSpec>): ResourceDiff<Map<String, ServerGroup>> {
    val current = resource.spec.resolve().byRegion()
    val desired = resource.spec.resolve().map { it.withDoubleCapacity() }.byRegion()
    return DefaultResourceDiff(desired, current)
  }

  override fun getDiffInImage(resource: Resource<ClusterSpec>): ResourceDiff<Map<String, ServerGroup>> {
    val current = resource.spec.resolve().byRegion()
    val desired = resource.spec.resolve().map { it.withADifferentImage() }.byRegion()
    return DefaultResourceDiff(desired, current)
  }

  private fun ServerGroup.withDoubleCapacity(): ServerGroup =
    copy(
      capacity = Capacity.DefaultCapacity(
        min = capacity.min * 2,
        max = capacity.max * 2,
        desired = capacity.desired * 2
      )
    )

  private fun ServerGroup.withManyEnabled(): ServerGroup =
    copy(
      onlyEnabledServerGroup = false
    )

  private fun ServerGroup.withADifferentImage(): ServerGroup =
    copy(launchConfiguration = launchConfiguration.copy(
      appVersion = "my-app-2.0.0",
      imageId = "id-2",
    ))
}
