package com.netflix.spinnaker.keel.ec2.resource

import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancer
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.CLOUD_PROVIDER
import com.netflix.spinnaker.keel.api.ec2.EC2_APPLICATION_LOAD_BALANCER_V1_1
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.ApplicationLoadBalancerModel
import com.netflix.spinnaker.keel.clouddriver.model.ApplicationLoadBalancerModel.ApplicationLoadBalancerListener
import com.netflix.spinnaker.keel.clouddriver.model.ApplicationLoadBalancerModel.TargetGroup
import com.netflix.spinnaker.keel.clouddriver.model.ApplicationLoadBalancerModel.TargetGroupMatcher
import com.netflix.spinnaker.keel.clouddriver.model.Network
import com.netflix.spinnaker.keel.clouddriver.model.SecurityGroupSummary
import com.netflix.spinnaker.keel.clouddriver.model.Subnet
import com.netflix.spinnaker.keel.core.parseMoniker
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.ec2.resolvers.ApplicationLoadBalancerDefaultsResolver
import com.netflix.spinnaker.keel.ec2.resolvers.ApplicationLoadBalancerNetworkResolver
import com.netflix.spinnaker.keel.ec2.toEc2Api
import com.netflix.spinnaker.keel.model.OrchestrationRequest
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.orca.OrcaTaskLauncher
import com.netflix.spinnaker.keel.orca.TaskRefResponse
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.serialization.configuredYamlMapper
import com.netflix.spinnaker.keel.test.resource
import de.danielbechler.diff.node.DiffNode.State.CHANGED
import de.danielbechler.diff.path.NodePath
import de.huxhorn.sulky.ulid.ULID
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.first
import strikt.assertions.get
import strikt.assertions.getValue
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import java.util.UUID
import io.mockk.coEvery as every
import io.mockk.coVerify as verify

@Suppress("UNCHECKED_CAST")
internal class ApplicationLoadBalancerHandlerTests : JUnit5Minutests {
  private val cloudDriverService = mockk<CloudDriverService>()
  private val cloudDriverCache = mockk<CloudDriverCache>()
  private val orcaService = mockk<OrcaService>()
  private val publisher: EventPublisher = mockk(relaxUnitFun = true)
  private val repository = mockk<KeelRepository> {
    // we're just using this to get notifications
    every { environmentFor(any()) } returns Environment("test")
  }

  private val taskLauncher = OrcaTaskLauncher(
    orcaService,
    repository,
    publisher
  )
  private val yamlMapper = configuredYamlMapper()

  private val normalizers: List<Resolver<*>> = listOf(
    ApplicationLoadBalancerDefaultsResolver(),
    ApplicationLoadBalancerNetworkResolver(cloudDriverCache)
  )

  private val yaml =
    """
    |---
    |moniker:
    |  app: testapp
    |  stack: managedogge
    |  detail: wow
    |locations:
    |  account: test
    |  vpc: vpc0
    |  subnet: internal (vpc0)
    |  regions:
    |  - name: us-east-1
    |    availabilityZones:
    |    - us-east-1c
    |    - us-east-1d
    |listeners:
    | - port: 80
    |   protocol: HTTPS
    |   certificateArn: this-is-my-certificate
    |targetGroups:
    | - name: managedogge-wow-tg
    |   port: 7001
    """.trimMargin()

  private val spec = yamlMapper.readValue<ApplicationLoadBalancerSpec>(yaml)
  private val resource = resource(
    kind = EC2_APPLICATION_LOAD_BALANCER_V1_1.kind,
    spec = spec
  )

  private val vpc = Network(CLOUD_PROVIDER, "vpc-23144", "vpc0", "test", "us-east-1")
  private val sub1 = Subnet("subnet-1", vpc.id, vpc.account, vpc.region, "${vpc.region}c", "internal (vpc0)")
  private val sub2 = Subnet("subnet-1", vpc.id, vpc.account, vpc.region, "${vpc.region}d", "internal (vpc0)")
  private val sg1 = SecurityGroupSummary("testapp-elb", "sg-55555", "vpc-1")

  private fun model(port: Int = 7001, healthCheckPort: String = "7001") = ApplicationLoadBalancerModel(
    moniker = null,
    loadBalancerName = "testapp-managedogge-wow",
    availabilityZones = setOf("us-east-1c", "us-east-1d"),
    vpcId = vpc.id,
    subnets = setOf(sub1.id, sub2.id),
    securityGroups = setOf(sg1.id),
    scheme = "internal",
    idleTimeout = 60,
    ipAddressType = "ipv4",
    listeners = listOf(
      ApplicationLoadBalancerListener(
        port = 80,
        protocol = "HTTPS",
        certificates = listOf(
          ApplicationLoadBalancerModel.Certificate(
            certificateArn = "this-is-my-certificate"
          )
        ),
        rules = emptyList(),
        defaultActions = listOf(
          ApplicationLoadBalancerModel.Action(
            order = 1,
            targetGroupName = "managedogge-wow-tg",
            type = "forward",
            redirectConfig = null
          )
        )
      )
    ),
    targetGroups = listOf(
      TargetGroup(
        targetGroupName = "managedogge-wow-tg",
        loadBalancerNames = listOf("testapp-managedogge-wow"),
        targetType = "instance",
        matcher = TargetGroupMatcher(httpCode = "200-299"),
        port = port,
        protocol = "HTTP",
        healthCheckEnabled = true,
        healthCheckTimeoutSeconds = 5,
        healthCheckPort = healthCheckPort,
        healthCheckProtocol = "HTTP",
        healthCheckPath = "/healthcheck",
        healthCheckIntervalSeconds = 10,
        healthyThresholdCount = 10,
        unhealthyThresholdCount = 2,
        vpcId = vpc.id,
        attributes = ApplicationLoadBalancerModel.TargetGroupAttributes(
          stickinessEnabled = false,
          deregistrationDelay = 300,
          stickinessType = "lb_cookie",
          stickinessDuration = 86400,
          slowStartDurationSeconds = 0
        )
      )
    )
  )

  fun tests() = rootContext<ApplicationLoadBalancerHandler> {
    fixture {
      ApplicationLoadBalancerHandler(
        cloudDriverService,
        cloudDriverCache,
        orcaService,
        taskLauncher,
        normalizers
      )
    }

    before {
      with(cloudDriverCache) {
        every { availabilityZonesBy(any(), any(), any(), vpc.region) } returns listOf("us-east-1c", "us-east-1d")
        every { networkBy(vpc.id) } returns vpc
        every { networkBy(vpc.name, vpc.account, vpc.region) } returns vpc
        every { subnetBy(any(), any(), any()) } returns sub1
        every { subnetBy(sub1.id) } returns sub1
        every { subnetBy(sub2.id) } returns sub2
        every { securityGroupById(vpc.account, vpc.region, sg1.id) } returns sg1
        every { securityGroupByName(vpc.account, vpc.region, sg1.name) } returns sg1
      }

      every { orcaService.orchestrate("keel@spinnaker", any()) } returns TaskRefResponse("/tasks/${UUID.randomUUID()}")
    }

    context("the ALB does not exist") {
      before {
        every { cloudDriverService.getApplicationLoadBalancer(any(), any(), any(), any(), any()) } returns emptyList()
      }

      test("the ALB is created with a generated defaultAction as none are in the spec") {
        runBlocking {
          val current = current(resource)
          val desired = desired(resource)
          upsert(resource, DefaultResourceDiff(desired = desired, current = current))
        }

        val slot = slot<OrchestrationRequest>()
        verify { orcaService.orchestrate("keel@spinnaker", capture(slot)) }

        expectThat(slot.captured.job.first()) {
          get("type").isEqualTo("upsertLoadBalancer")
        }

        val listeners = slot.captured.job.first()["listeners"] as Collection<Map<String, Any?>>

        expectThat(listeners.first()) {
          get("defaultActions")
            .isA<Collection<*>>()
            .first()
            .isEqualTo(model().listeners.first().defaultActions.first().toEc2Api())
          get("certificates")
            .isA<Collection<Map<String, Any?>>>()
            .first()
            .get("certificateArn")
            .isEqualTo(model().listeners.first().certificates?.first()?.certificateArn)
        }
      }
    }

    context("the ALB has been created") {
      before {
        every { cloudDriverService.getApplicationLoadBalancer(any(), any(), any(), any(), any()) } returns listOf(model())
      }

      test("the diff is clean") {
        val diff = runBlocking {
          val current = current(resource)
          val desired = desired(resource)
          DefaultResourceDiff(desired = desired, current = current)
        }

        expectThat(diff.diff.childCount()).isEqualTo(0)
      }

      test("export generates a valid spec for the deployed ALB") {
        val exportable = Exportable(
          cloudProvider = "aws",
          account = "test",
          user = "fzlem@netflix.com",
          moniker = parseMoniker("testapp-managedogge-wow"),
          regions = setOf("us-east-1"),
          kind = supportedKind.kind
        )
        val export = runBlocking {
          export(exportable)
        }

        runBlocking {
          // Export differs from the model prior to the application of resolvers
          val unresolvedDiff = DefaultResourceDiff(resource, resource.copy(spec = export))
          expectThat(unresolvedDiff.hasChanges())
            .isTrue()
          // But diffs cleanly after resolvers are applied
          val resolvedDiff = DefaultResourceDiff(
            desired(resource),
            desired(resource.copy(spec = export))
          )
          expectThat(resolvedDiff.hasChanges())
            .isFalse()
        }
      }
    }

    context("the ALB has a traffic port different from health check port") {
      before {
        every { cloudDriverService.getApplicationLoadBalancer(any(), any(), any(), any(), any()) } returns listOf(model(port = 8080, healthCheckPort = "8081"))
      }

      test("the ports match as expected") {
        val current: Map<String, ApplicationLoadBalancer> = runBlocking {
          current(resource)
        }

        expectThat(current)
          .getValue("us-east-1")
          .get { targetGroups }
          .first()
          .and { get { port }.isEqualTo(8080) }
          .and { get { healthCheckPort }.isEqualTo(8081) }
      }
    }

    context("The ALB's health check port is specified as 'traffic-port'") {
      before {
        every { cloudDriverService.getApplicationLoadBalancer(any(), any(), any(), any(), any()) } returns listOf(model(port = 8080, healthCheckPort = "traffic-port"))
      }

      test("the health check port is set to the same value as the traffic port") {
        val current: Map<String, ApplicationLoadBalancer> = runBlocking {
          current(resource)
        }

        expectThat(current)
          .getValue("us-east-1")
          .get { targetGroups }
          .first()
          .and { get { port }.isEqualTo(8080) }
          .and { get { healthCheckPort }.isEqualTo(8080) }
      }
    }

    context("the ALB spec has been updated") {
      before {
        every { cloudDriverService.getApplicationLoadBalancer(any(), any(), any(), any(), any()) } returns listOf(model())
      }

      test("the diff reflects the new spec and is upserted") {
        val slot = slot<OrchestrationRequest>()
        every { orcaService.orchestrate("keel@spinnaker", capture(slot)) } answers { TaskRefResponse(ULID().nextULID()) }

        val tgroup = resource.spec.targetGroups.first().copy(port = 7505)
        val newResource = resource.copy(spec = resource.spec.copy(targetGroups = setOf(tgroup)))

        runBlocking {
          val current = current(newResource)
          val desired = desired(newResource)
          val diff = DefaultResourceDiff(desired = desired, current = current)

          expectThat(diff.diff) {
            get { childCount() }.isEqualTo(1)
            get {
              getChild(NodePath.startBuilding().mapKey("us-east-1").propertyName("targetGroups").build()).state
            }.isEqualTo(CHANGED)
          }

          upsert(newResource, diff)
        }

        expectThat(slot.captured.job.first()) {
          get("type").isEqualTo("upsertLoadBalancer")
        }
      }
    }
  }
}
