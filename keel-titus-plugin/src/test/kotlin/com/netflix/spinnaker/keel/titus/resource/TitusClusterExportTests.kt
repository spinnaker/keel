package com.netflix.spinnaker.keel.titus.resource

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.SimpleRegionSpec
import com.netflix.spinnaker.keel.api.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.BRANCH_JOB_COMMIT_BY_JOB
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.api.serviceAccount
import com.netflix.spinnaker.keel.api.titus.CLOUD_PROVIDER
import com.netflix.spinnaker.keel.api.titus.SPINNAKER_TITUS_API_V1
import com.netflix.spinnaker.keel.api.titus.cluster.ResourcesSpec
import com.netflix.spinnaker.keel.api.titus.cluster.TitusClusterHandler
import com.netflix.spinnaker.keel.api.titus.cluster.TitusClusterSpec
import com.netflix.spinnaker.keel.api.titus.cluster.TitusServerGroupSpec
import com.netflix.spinnaker.keel.api.titus.cluster.resolve
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.DockerImage
import com.netflix.spinnaker.keel.clouddriver.model.Resources
import com.netflix.spinnaker.keel.clouddriver.model.SecurityGroupSummary
import com.netflix.spinnaker.keel.clouddriver.model.TitusActiveServerGroup
import com.netflix.spinnaker.keel.core.api.Capacity
import com.netflix.spinnaker.keel.core.api.ClusterDependencies
import com.netflix.spinnaker.keel.core.api.RedBlack
import com.netflix.spinnaker.keel.docker.DigestProvider
import com.netflix.spinnaker.keel.docker.ReferenceProvider
import com.netflix.spinnaker.keel.exceptions.DockerArtifactExportError
import com.netflix.spinnaker.keel.orca.ClusterExportHelper
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.orca.OrcaTaskLauncher
import com.netflix.spinnaker.keel.orca.TaskRefResponse
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expect
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.containsKey
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull

internal class TitusClusterExportTests : JUnit5Minutests {
  val cloudDriverService = mockk<CloudDriverService>()
  val cloudDriverCache = mockk<CloudDriverCache>()
  val orcaService = mockk<OrcaService>()
  val resolvers = emptyList<Resolver<TitusClusterSpec>>()
  val repository = mockk<KeelRepository>()
  val publisher: ApplicationEventPublisher = mockk(relaxUnitFun = true)
  val taskLauncher = OrcaTaskLauncher(
    orcaService,
    repository,
    publisher
  )
  val clock = Clock.systemUTC()
  val clusterExportHelper = mockk<ClusterExportHelper>(relaxed = true)

  val sg1West = SecurityGroupSummary("keel", "sg-325234532", "vpc-1")
  val sg2West = SecurityGroupSummary("keel-elb", "sg-235425234", "vpc-1")
  val sg1East = SecurityGroupSummary("keel", "sg-279585936", "vpc-1")
  val sg2East = SecurityGroupSummary("keel-elb", "sg-610264122", "vpc-1")

  val titusAccount = "titustest"
  val awsAccount = "test"

  val container = DigestProvider(
    organization = "spinnaker",
    image = "keel",
    digest = "sha:1111"
  )

  val spec = TitusClusterSpec(
    moniker = Moniker(app = "keel", stack = "test"),
    locations = SimpleLocations(
      account = titusAccount,
      regions = setOf(SimpleRegionSpec("us-east-1"), SimpleRegionSpec("us-west-2"))
    ),
    _defaults = TitusServerGroupSpec(
      container = container,
      capacity = Capacity(1, 6, 4),
      dependencies = ClusterDependencies(
        loadBalancerNames = setOf("keel-test-frontend"),
        securityGroupNames = setOf(sg1West.name)
      )
    ),
    containerProvider = container
  )

  val serverGroups = spec.resolve()
  val serverGroupEast = serverGroups.first { it.location.region == "us-east-1" }
  val serverGroupWest = serverGroups.first { it.location.region == "us-west-2" }

  val activeServerGroupResponseEast = serverGroupEast.toClouddriverResponse(listOf(sg1East, sg2East), awsAccount)
  val activeServerGroupResponseWest = serverGroupWest.toClouddriverResponse(listOf(sg1West, sg2West), awsAccount)

  val resource = resource(
    kind = SPINNAKER_TITUS_API_V1.qualify("cluster"),
    spec = spec
  )

  val exportable = Exportable(
    cloudProvider = "titus",
    account = spec.locations.account,
    user = "fzlem@netflix.com",
    moniker = spec.moniker,
    regions = spec.locations.regions.map { it.name }.toSet(),
    kind = SPINNAKER_TITUS_API_V1.qualify("cluster")
  )

  val image = DockerImage(
    account = "testregistry",
    repository = "emburns/spin-titus-demo",
    tag = "1",
    digest = "sha:1111"
  )

  val images = listOf(
    image,
    image.copy(tag = "2", digest = "sha:2222")
  )

  val branchJobShaImages = listOf(
    image.copy(tag = "latest"),
    image.copy(tag = "master-h10.62bbbd6"),
    image.copy(tag = "master-h11.4e26fbd", digest = "sha:2222")
  )

  val weirdImages = listOf(
    image.copy(tag = "blahblah")
  )

  fun tests() = rootContext<TitusClusterHandler> {
    fixture {
      TitusClusterHandler(
        cloudDriverService,
        cloudDriverCache,
        orcaService,
        clock,
        taskLauncher,
        publisher,
        resolvers,
        clusterExportHelper
      )
    }

    before {
      with(cloudDriverCache) {
        every { securityGroupById(awsAccount, "us-west-2", sg1West.id) } returns sg1West
        every { securityGroupById(awsAccount, "us-west-2", sg2West.id) } returns sg2West
        every { securityGroupByName(awsAccount, "us-west-2", sg1West.name) } returns sg1West
        every { securityGroupByName(awsAccount, "us-west-2", sg2West.name) } returns sg2West

        every { securityGroupById(awsAccount, "us-east-1", sg1East.id) } returns sg1East
        every { securityGroupById(awsAccount, "us-east-1", sg2East.id) } returns sg2East
        every { securityGroupByName(awsAccount, "us-east-1", sg1East.name) } returns sg1East
        every { securityGroupByName(awsAccount, "us-east-1", sg2East.name) } returns sg2East

        coEvery { cloudDriverService.getAccountInformation(titusAccount) } returns mapOf(
          "awsAccount" to awsAccount,
          "registry" to awsAccount + "registry"
        )
      }
      coEvery { orcaService.orchestrate(resource.serviceAccount, any()) } returns TaskRefResponse("/tasks/${UUID.randomUUID()}")
      every { repository.environmentFor(any()) } returns Environment("test")
      coEvery {
        clusterExportHelper.discoverDeploymentStrategy("titus", "titustest", "keel", any())
      } returns RedBlack()
    }

    after {
      confirmVerified(orcaService)
      clearAllMocks()
    }

    context("export without overrides") {
      before {
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-east-1") } returns activeServerGroupResponseEast
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-west-2") } returns activeServerGroupResponseWest
        coEvery { cloudDriverService.findDockerImages("testregistry", (spec.defaults.container!! as DigestProvider).repository()) } returns images
        coEvery { cloudDriverService.getAccountInformation(titusAccount) } returns mapOf("registry" to "testregistry")
      }

      context("exported titus cluster spec") {
        test("has the expected basic properties with default values omitted") {
          val cluster = runBlocking {
            export(exportable)
          }
          with(cluster) {
            expect {
              that(locations.regions).hasSize(2)
              that(overrides).hasSize(0)
              that(spec.defaults.constraints).isNull()
              that(spec.defaults.entryPoint).isNull()
              that(spec.defaults.migrationPolicy).isNull()
              that(spec.defaults.resources).isNull()
              that(spec.defaults.iamProfile).isNull()
              that(spec.defaults.capacityGroup).isNull()
              that(spec.defaults.env).isNull()
              that(spec.defaults.containerAttributes).isNull()
              that(spec.defaults.tags).isNull()
              that(containerProvider).isA<ReferenceProvider>()
            }
          }
        }
      }

      context("exported artifact") {
        context("tags are just increasing numbers") {
          before {
            coEvery { cloudDriverService.findDockerImages("testregistry", (spec.defaults.container!! as DigestProvider).repository()) } returns images
          }
          test("tag strategy is chosen as INCREASING_TAG") {
            val artifact = runBlocking {
              exportArtifact(exportable)
            }
            expectThat(artifact)
              .isA<DockerArtifact>()
              .get { tagVersionStrategy }.isEqualTo(TagVersionStrategy.INCREASING_TAG)
          }
        }
        context("tags are branch-job.sha") {
          before {
            coEvery { cloudDriverService.findDockerImages("testregistry", (spec.defaults.container!! as DigestProvider).repository()) } returns branchJobShaImages
          }

          test("tag strategy is chosen as BRANCH_JOB_COMMIT_BY_JOB") {
            val artifact = runBlocking {
              exportArtifact(exportable)
            }
            expectThat(artifact)
              .isA<DockerArtifact>()
              .get { tagVersionStrategy }.isEqualTo(BRANCH_JOB_COMMIT_BY_JOB)
          }
        }

        context("tags are just string garbage") {
          before {
            coEvery { cloudDriverService.findDockerImages("testregistry", (spec.defaults.container!! as DigestProvider).repository()) } returns weirdImages
          }

          test("exception is throw") {
            expectThrows<DockerArtifactExportError> {
              exportArtifact(exportable)
            }
          }
        }
      }
    }

    context("export with overrides") {
      before {
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-east-1") } returns
          activeServerGroupResponseEast
            .withDifferentEnv()
            .withDifferentEntryPoint()
            .withDifferentResources()
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-west-2") } returns
          activeServerGroupResponseWest
            .withDoubleCapacity()

        coEvery { cloudDriverService.findDockerImages("testregistry", container.repository()) } returns images
        coEvery { cloudDriverService.getAccountInformation(titusAccount) } returns mapOf("registry" to "testregistry")
      }

      context("exported titus cluster spec") {

        test("has overrides matching differences in the server groups") {
          val cluster = runBlocking {
            export(exportable)
          }
          with(cluster) {
            expect {
              that(overrides).hasSize(1)
              that(overrides).containsKey("us-east-1")
              val override = overrides["us-east-1"]
              that(override).isNotNull().get { entryPoint }.isNotNull()
              that(override).isNotNull().get { capacity }.isNotNull()
              that(override).isNotNull().get { env }.isNotNull()
              that(override).isNotNull().get { resources }.isEqualTo(ResourcesSpec(
                cpu = 4,
                disk = 81920,
                gpu = 0,
                memory = 16384,
                networkMbps = 700
              ))

              that(locations.regions).hasSize(2)
            }
          }
        }

        test("has default values in overrides omitted") {
          val cluster = runBlocking {
            export(exportable)
          }
          with(cluster) {
            expectThat(overrides).containsKey("us-east-1")
            expectThat(overrides["us-east-1"]).isNotNull()
            val override = overrides["us-east-1"]!!
            expectThat(override) {
              get { constraints }.isNull()
              get { migrationPolicy }.isNull()
              get { iamProfile }.isNull()
              get { capacityGroup }.isNull()
              get { containerAttributes }.isNull()
              get { tags }.isNull()
            }
          }
        }
      }
    }
  }

  private suspend fun CloudDriverService.titusActiveServerGroup(user: String, region: String) = titusActiveServerGroup(
    user = user,
    app = spec.moniker.app,
    account = spec.locations.account,
    cluster = spec.moniker.toString(),
    region = region,
    cloudProvider = CLOUD_PROVIDER
  )

  private fun TitusActiveServerGroup.withDoubleCapacity(): TitusActiveServerGroup =
    copy(
      capacity = Capacity(
        min = capacity.min * 2,
        max = capacity.max * 2,
        desired = capacity.desired!! * 2
      )
    )

  private fun TitusActiveServerGroup.withDifferentEnv(): TitusActiveServerGroup =
    copy(env = mapOf("foo" to "bar"))

  private fun TitusActiveServerGroup.withDifferentEntryPoint(): TitusActiveServerGroup =
    copy(entryPoint = "/bin/blah")

  private fun TitusActiveServerGroup.withDifferentResources(): TitusActiveServerGroup =
    copy(resources = Resources(
      cpu = 4,
      disk = 81920,
      gpu = 0,
      memory = 16384,
      networkMbps = 700
    ))
}
