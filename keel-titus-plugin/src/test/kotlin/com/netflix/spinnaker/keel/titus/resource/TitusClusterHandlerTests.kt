/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.titus.resource

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.Highlander
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.RedBlack
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.SimpleRegionSpec
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.BRANCH_JOB_COMMIT_BY_JOB
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.INCREASING_TAG
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.SEMVER_JOB_COMMIT_BY_SEMVER
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.SEMVER_TAG
import com.netflix.spinnaker.keel.api.ec2.Capacity
import com.netflix.spinnaker.keel.api.ec2.ClusterDependencies
import com.netflix.spinnaker.keel.api.ec2.ServerGroup.InstanceCounts
import com.netflix.spinnaker.keel.api.events.ArtifactVersionDeployed
import com.netflix.spinnaker.keel.api.events.ArtifactVersionDeploying
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.api.titus.TitusClusterSpec
import com.netflix.spinnaker.keel.api.titus.TitusServerGroup
import com.netflix.spinnaker.keel.api.titus.TitusServerGroupSpec
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.DockerImage
import com.netflix.spinnaker.keel.clouddriver.model.SecurityGroupSummary
import com.netflix.spinnaker.keel.clouddriver.model.ServerGroupCollection
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.docker.DigestProvider
import com.netflix.spinnaker.keel.model.OrchestrationRequest
import com.netflix.spinnaker.keel.orca.ClusterExportHelper
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.orca.OrcaTaskLauncher
import com.netflix.spinnaker.keel.orca.TaskRefResponse
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.test.resource
import com.netflix.spinnaker.keel.titus.CLOUD_PROVIDER
import com.netflix.spinnaker.keel.titus.TITUS_CLUSTER_V1
import com.netflix.spinnaker.keel.titus.TitusClusterHandler
import com.netflix.spinnaker.keel.titus.byRegion
import com.netflix.spinnaker.keel.titus.resolve
import com.netflix.spinnaker.keel.titus.resolveCapacity
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import strikt.api.Assertion
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.containsKey
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotNull
import strikt.assertions.map
import java.time.Clock
import java.time.Duration
import java.util.UUID

// todo eb: we could probably have generic cluster tests
// where you provide the correct info for the spec and active server groups
class TitusClusterHandlerTests : JUnit5Minutests {
  val cloudDriverService = mockk<CloudDriverService>()
  val cloudDriverCache = mockk<CloudDriverCache>()
  val orcaService = mockk<OrcaService>()
  val resolvers = emptyList<Resolver<TitusClusterSpec>>()
  val repository = mockk<KeelRepository>()
  val publisher: EventPublisher = mockk(relaxUnitFun = true)
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

  val digestProvider = DigestProvider(
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
      capacity = Capacity(1, 6, 4),
      dependencies = ClusterDependencies(
        loadBalancerNames = setOf("keel-test-frontend"),
        securityGroupNames = setOf(sg1West.name)
      )
    ),
    container = digestProvider
  )

  val serverGroups = spec.resolve()
  val serverGroupEast = serverGroups.first { it.location.region == "us-east-1" }
  val serverGroupWest = serverGroups.first { it.location.region == "us-west-2" }

  val activeServerGroupResponseEast = serverGroupEast.toClouddriverResponse(listOf(sg1East, sg2East), awsAccount)
  val activeServerGroupResponseWest = serverGroupWest.toClouddriverResponse(listOf(sg1West, sg2West), awsAccount)

  val allServerGroups = ServerGroupCollection(
    titusAccount,
    setOf(
      activeServerGroupResponseEast.toAllServerGroupsResponse(),
      activeServerGroupResponseWest.toAllServerGroupsResponse()
    )
  )

  val resource = resource(
    kind = TITUS_CLUSTER_V1.kind,
    spec = spec
  )

  val exportable = Exportable(
    cloudProvider = "titus",
    account = spec.locations.account,
    user = "fzlem@netflix.com",
    moniker = spec.moniker,
    regions = spec.locations.regions.map { it.name }.toSet(),
    kind = TITUS_CLUSTER_V1.kind
  )

  val images = listOf(
    DockerImage(
      account = "testregistry",
      repository = "emburns/spin-titus-demo",
      tag = "1",
      digest = "sha:2222"
    ),
    DockerImage(
      account = "testregistry",
      repository = "emburns/spin-titus-demo",
      tag = "2",
      digest = "sha:3333"
    )
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

    context("the cluster does not exist or has no active server groups") {
      before {
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-east-1") } returns activeServerGroupResponseEast
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-west-2") } throws RETROFIT_NOT_FOUND
        coEvery { cloudDriverService.findDockerImages("testregistry", "spinnaker/keel", any(), any()) } returns
          listOf(DockerImage("testregistry", "spinnaker/keel", "master-h2.blah", "sha:1111"))
        coEvery { cloudDriverService.listTitusServerGroups(any(), any(), any(), any()) } throws RETROFIT_NOT_FOUND
      }

      test("the current model is null") {
        val current = runBlocking {
          current(resource)
        }
        expectThat(current)
          .hasSize(1)
          .not()
          .containsKey("us-west-2")
      }

      test("resolving diff a diff creates a new server group") {
        runBlocking {
          upsert(resource, DefaultResourceDiff(serverGroups.byRegion(), emptyMap()))
        }

        val slot = slot<OrchestrationRequest>()
        coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }

        expectThat(slot.captured.job.first()) {
          get("type").isEqualTo("createServerGroup")
        }
      }
    }

    context("the cluster has healthy active server groups") {
      before {
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-east-1") } returns activeServerGroupResponseEast
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-west-2") } returns activeServerGroupResponseWest
        coEvery { cloudDriverService.findDockerImages("testregistry", "spinnaker/keel", any(), any()) } returns
          listOf(DockerImage("testregistry", "spinnaker/keel", "master-h2.blah", "sha:1111"))
        coEvery { cloudDriverService.listTitusServerGroups(any(), any(), any(), any())} returns allServerGroups
      }

      // TODO: test for multiple server group response
      derivedContext<Map<String, TitusServerGroup>>("fetching the current server group state") {
        deriveFixture {
          runBlocking {
            current(resource)
          }
        }

        test("the current model is converted to a set of server group") {
          expectThat(this).isNotEmpty()
        }

        test("the server group name is derived correctly") {
          expectThat(values)
            .map { it.name }
            .containsExactlyInAnyOrder(
              activeServerGroupResponseEast.name,
              activeServerGroupResponseWest.name
            )
        }

        test("a deployed event fired") {
          verify { publisher.publishEvent(any<ArtifactVersionDeployed>()) }
        }
      }
    }

    context("the cluster has unhealthy active server groups") {
      before {
        val instanceCounts = InstanceCounts(1, 0, 0, 1, 0, 0)
        val east = serverGroupEast.toClouddriverResponse(listOf(sg1East, sg2East), awsAccount, instanceCounts)
        val west = serverGroupWest.toClouddriverResponse(listOf(sg1West, sg2West), awsAccount, instanceCounts)
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-east-1") } returns east
        coEvery { cloudDriverService.titusActiveServerGroup(any(), "us-west-2") } returns west
        coEvery { cloudDriverService.findDockerImages("testregistry", "spinnaker/keel", any(), any()) } returns
          listOf(DockerImage("testregistry", "spinnaker/keel", "master-h2.blah", "sha:1111"))
        coEvery { cloudDriverService.listTitusServerGroups(any(), any(), any(), any())} returns
          ServerGroupCollection(titusAccount, setOf(east.toAllServerGroupsResponse(), west.toAllServerGroupsResponse()))
      }

      // TODO: test for multiple server group response
      derivedContext<Map<String, TitusServerGroup>>("fetching the current server group state") {
        deriveFixture {
          runBlocking {
            current(resource)
          }
        }

        test("no deployed event firs") {
          verify(exactly = 0) { publisher.publishEvent(any<ArtifactVersionDeployed>()) }
        }
      }
    }

    context("a diff has been detected") {
      context("the diff is only in capacity") {

        val modified = setOf(
          serverGroupEast.copy(name = activeServerGroupResponseEast.name),
          serverGroupWest.copy(name = activeServerGroupResponseWest.name).withDoubleCapacity()
        )
        val diff = DefaultResourceDiff(
          serverGroups.byRegion(),
          modified.byRegion()
        )

        test("resolving diff resizes the current server group") {
          runBlocking {
            upsert(resource, diff)
          }

          val slot = slot<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }

          expectThat(slot.captured.job.first()) {
            get("type").isEqualTo("resizeServerGroup")
            get("capacity").isEqualTo(
              spec.resolveCapacity("us-west-2").let {
                mapOf(
                  "min" to it.min,
                  "max" to it.max,
                  "desired" to it.desired
                )
              }
            )
            get("serverGroupName").isEqualTo(activeServerGroupResponseWest.name)
          }
        }
      }

      context("the diff is only in enabled/disabled status") {
        val east = serverGroupEast.toMultiServerGroupResponse(listOf(sg1East, sg2East), awsAccount, allEnabled = true)
        val west = serverGroupWest.toMultiServerGroupResponse(listOf(sg1West, sg2West), awsAccount)

        val modified = setOf(
          serverGroupEast.copy(name = activeServerGroupResponseEast.name, onlyEnabledServerGroup = false),
          serverGroupWest.copy(name = activeServerGroupResponseWest.name)
        )
        val diff = DefaultResourceDiff(
          serverGroups.byRegion(),
          modified.byRegion()
        )

        before {
          coEvery { cloudDriverService.listTitusServerGroups(any(), any(), any(), any())} returns
            ServerGroupCollection(
              titusAccount,
              east + west
            )
          coEvery { cloudDriverService.findDockerImages("testregistry", "spinnaker/keel", any(), any(), any()) } returns
            listOf(
              DockerImage("testregistry", "spinnaker/keel", "master-h2.blah", "sha:1111")
            )
        }

        test("resolving diff disables the oldest enabled server group") {
          runBlocking {
            upsert(resource, diff)
          }

          val slot = slot<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }

          expectThat(slot.captured.job.first()) {
            get("type").isEqualTo("disableServerGroup")
            get("asgName").isEqualTo(east.sortedBy { it.createdTime }.first().name)
          }
        }
      }

      context("a version diff with one tag per sha deploys by tag") {
        before {
          coEvery { cloudDriverService.findDockerImages("testregistry", "spinnaker/keel", any(), any(), any()) } returns
            listOf(
              DockerImage("testregistry", "spinnaker/keel", "master-h2.blah", "sha:1111")
            )
        }

        val modified = setOf(
          serverGroupEast.copy(name = activeServerGroupResponseEast.name),
          serverGroupWest.copy(name = activeServerGroupResponseWest.name).withDoubleCapacity().withDifferentRuntimeOptions()
        )
        val diff = DefaultResourceDiff(
          serverGroups.byRegion(),
          modified.byRegion()
        )

        test("resolving diff clones the current server group by tag") {
          runBlocking {
            upsert(resource, diff)
          }

          val slot = slot<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }

          expectThat(slot.captured.job.first()) {
            // single tag for a digest, so deploy by tag
            get("tag").isNotNull().isEqualTo("master-h2.blah")
          }
        }
      }

      context("the diff is something other than just capacity") {
        before {
          coEvery { cloudDriverService.findDockerImages("testregistry", "spinnaker/keel", any(), any(), any()) } returns
            listOf(
              DockerImage("testregistry", "spinnaker/keel", "master-h2.blah", "sha:1111"),
              DockerImage("testregistry", "spinnaker/keel", "im-master-now", "sha:1111")
            )
        }

        val modified = setOf(
          serverGroupEast.copy(name = activeServerGroupResponseEast.name),
          serverGroupWest.copy(name = activeServerGroupResponseWest.name).withDoubleCapacity().withDifferentRuntimeOptions()
        )
        val diff = DefaultResourceDiff(
          serverGroups.byRegion(),
          modified.byRegion()
        )

        test("events are fired for the artifact deploying") {
          runBlocking {
            upsert(resource, diff)
          }
          val slot = slot<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }
          verify { publisher.publishEvent(ArtifactVersionDeploying(resource.id, "master-h2.blah")) }
          verify { publisher.publishEvent(ArtifactVersionDeploying(resource.id, "im-master-now")) }
        }

        test("resolving diff clones the current server group by digest") {
          runBlocking {
            upsert(resource, diff)
          }

          val slot = slot<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }

          expectThat(slot.captured.job.first()) {
            get("type").isEqualTo("createServerGroup")
            get("source").isEqualTo(
              mapOf(
                "account" to activeServerGroupResponseWest.placement.account,
                "region" to activeServerGroupResponseWest.region,
                "asgName" to activeServerGroupResponseWest.name
              )
            )
            // multiple tags for a digest, so deploy by digest
            get("digest").isNotNull().isEqualTo("sha:1111")
          }
        }

        test("the default deploy strategy is used") {
          val deployWith = RedBlack()
          runBlocking {
            upsert(resource, diff)
          }

          val slot = slot<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }

          expectThat(slot.captured.job.first()) {
            get("strategy").isEqualTo("redblack")
            get("delayBeforeDisableSec").isEqualTo(deployWith.delayBeforeDisable?.seconds)
            get("delayBeforeScaleDownSec").isEqualTo(deployWith.delayBeforeScaleDown?.seconds)
            get("rollback").isA<Map<String, Any?>>().get("onFailure").isEqualTo(deployWith.rollbackOnFailure)
            get("scaleDown").isEqualTo(deployWith.resizePreviousToZero)
            get("maxRemainingAsgs").isEqualTo(deployWith.maxServerGroups)
          }
        }

        test("the deploy strategy is configured") {
          val deployWith = RedBlack(
            resizePreviousToZero = true,
            delayBeforeDisable = Duration.ofMinutes(1),
            delayBeforeScaleDown = Duration.ofMinutes(5),
            maxServerGroups = 3
          )
          runBlocking {
            upsert(resource.copy(spec = resource.spec.copy(deployWith = deployWith)), diff)
          }

          val slot = slot<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }

          expectThat(slot.captured.job.first()) {
            get("strategy").isEqualTo("redblack")
            get("delayBeforeDisableSec").isEqualTo(deployWith.delayBeforeDisable?.seconds)
            get("delayBeforeScaleDownSec").isEqualTo(deployWith.delayBeforeScaleDown?.seconds)
            get("rollback").isA<Map<String, Any?>>().get("onFailure").isEqualTo(deployWith.rollbackOnFailure)
            get("scaleDown").isEqualTo(deployWith.resizePreviousToZero)
            get("maxRemainingAsgs").isEqualTo(deployWith.maxServerGroups)
          }
        }

        test("a different deploy strategy is used") {
          runBlocking {
            upsert(resource.copy(spec = resource.spec.copy(deployWith = Highlander())), diff)
          }

          val slot = slot<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(resource.serviceAccount, capture(slot)) }

          expectThat(slot.captured.job.first()) {
            get("strategy").isEqualTo("highlander")
            not().containsKey("delayBeforeDisableSec")
            not().containsKey("delayBeforeScaleDownSec")
            not().containsKey("rollback")
            not().containsKey("scaleDown")
            not().containsKey("maxRemainingAsgs")
          }
        }
      }

      context("multiple server groups have a diff") {
        before {
          coEvery { cloudDriverService.findDockerImages("testregistry", "spinnaker/keel", any(), any(), any()) } returns
            listOf(DockerImage("testregistry", "spinnaker/keel", "master-h2.blah", "sha:1111"))
        }
        val modified = setOf(
          serverGroupEast.copy(name = activeServerGroupResponseEast.name).withDifferentRuntimeOptions(),
          serverGroupWest.copy(name = activeServerGroupResponseWest.name).withDoubleCapacity()
        )
        val diff = DefaultResourceDiff(
          serverGroups.byRegion(),
          modified.byRegion()
        )

        before {
          runBlocking {
            upsert(resource, diff)
          }
        }

        test("resolving diff launches one task per server group") {
          val tasks = mutableListOf<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(any(), capture(tasks)) }

          expectThat(tasks)
            .hasSize(2)
            .map { it.job.first()["type"] }
            .containsExactlyInAnyOrder("createServerGroup", "resizeServerGroup")
        }

        test("each task has a distinct correlation id") {
          val tasks = mutableListOf<OrchestrationRequest>()
          coVerify { orcaService.orchestrate(any(), capture(tasks)) }

          expectThat(tasks)
            .hasSize(2)
            .map { it.trigger.correlationId }
            .containsDistinctElements()
        }
      }
    }

    context("figuring out tagging strategy") {
      val image = DockerImage(
        account = "testregistry",
        repository = "emburns/spin-titus-demo",
        tag = "12",
        digest = "sha:1111"
      )
      test("number") {
        expectThat(deriveVersioningStrategy(image.tag)).isEqualTo(INCREASING_TAG)
      }
      test("semver with v") {
        expectThat(deriveVersioningStrategy("v1.12.3-rc.1")).isEqualTo(SEMVER_TAG)
      }
      test("semver without v") {
        expectThat(deriveVersioningStrategy("1.12.3-rc.1")).isEqualTo(SEMVER_TAG)
      }
      test("branch-job-commit") {
        expectThat(deriveVersioningStrategy("master-h3.2317144")).isEqualTo(BRANCH_JOB_COMMIT_BY_JOB)
      }
      test("semver-job-commit parses to semver version") {
        expectThat(deriveVersioningStrategy("v1.12.3-rc.1-h1196.49b8dc5")).isEqualTo(SEMVER_JOB_COMMIT_BY_SEMVER)
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
}

private fun TitusServerGroup.withDoubleCapacity(): TitusServerGroup =
  copy(
    capacity = Capacity(
      min = capacity.min * 2,
      max = capacity.max * 2,
      desired = capacity.desired!! * 2
    )
  )

private fun TitusServerGroup.withDifferentRuntimeOptions(): TitusServerGroup =
  copy(capacityGroup = "aDifferentGroup")

private fun <E, T : Iterable<E>> Assertion.Builder<T>.containsDistinctElements() =
  assert("contains distinct elements") { subject ->
    val duplicates = subject
      .associateWith { elem -> subject.count { it == elem } }
      .filterValues { it > 1 }
      .keys
    when (duplicates.size) {
      0 -> pass()
      1 -> fail(duplicates.first(), "The element %s occurs more than once")
      else -> fail(duplicates, "The elements %s occur more than once")
    }
  }

val RETROFIT_NOT_FOUND = HttpException(
  Response.error<Any>(404, "".toResponseBody("application/json".toMediaTypeOrNull()))
)
