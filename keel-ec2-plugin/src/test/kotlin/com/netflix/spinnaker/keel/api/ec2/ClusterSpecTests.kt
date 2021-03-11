package com.netflix.spinnaker.keel.api.ec2

import com.fasterxml.jackson.databind.InjectableValues
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.SubnetAwareRegionSpec
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec.HealthSpec
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec.ServerGroupSpec
import com.netflix.spinnaker.keel.api.ec2.HealthCheckType.ELB
import com.netflix.spinnaker.keel.ec2.jackson.registerKeelEc2ApiModule
import com.netflix.spinnaker.keel.test.configuredTestObjectMapper
import com.netflix.spinnaker.keel.test.configuredTestYamlMapper
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.all
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.java.propertiesAreEqualTo
import java.time.Duration

internal class ClusterSpecTests : JUnit5Minutests {

  fun tests() = rootContext<Fixture> {
    context("a spec with multiple regions and minimal configuration") {
      fixture { Fixture() }

      mapOf(
        "YAML" to configuredTestYamlMapper().registerKeelEc2ApiModule(),
        "JSON" to configuredTestObjectMapper().registerKeelEc2ApiModule()
      ).forEach { (format, mapper) ->
        test("can serialize and deserialize as $format") {
          val text = mapper.writeValueAsString(spec)
          val tree = mapper.readValue<ClusterSpec>(text)
          expectThat(tree).propertiesAreEqualTo(spec)
        }
      }

      test("there is one server group per region specified in the cluster") {
        expectThat(result).hasSize(spec.locations.regions.size)
      }

      test("image resolution is applied to all server groups") {
        expect {
          that(result).all {
            get { launchConfiguration.appVersion }
              .isEqualTo(appVersion)
          }
          that(usEastServerGroup.launchConfiguration.imageId).isEqualTo(usEastImageId)
          that(usWestServerGroup.launchConfiguration.imageId).isEqualTo(usWestImageId)
        }
      }

      test("configuration is applied from the cluster-wide defaults when not overridden in region") {
        expectThat(result)
          .all {
            get { launchConfiguration.instanceType }
              .isEqualTo(spec.defaults.launchConfiguration?.instanceType)
          }
      }

      test("configuration is applied from the region when not present in the cluster-wide defaults") {
        expectThat(usWestServerGroup)
          .get { launchConfiguration.keyPair }
          .isEqualTo(spec.overrides["us-west-2"]?.launchConfiguration?.keyPair)
      }

      test("cluster-wide default configuration can be overridden by regions") {
        expect {
          that(usEastServerGroup.launchConfiguration.iamRole)
            .isEqualTo(spec.overrides["us-east-1"]?.launchConfiguration?.iamRole)
          that(usWestServerGroup.launchConfiguration.iamRole)
            .isEqualTo(spec.defaults.launchConfiguration?.iamRole)
        }
      }

      test("override dependencies are merged with cluster-wide defaults") {
        expect {
          that(usEastServerGroup.dependencies.loadBalancerNames)
            .contains(spec.defaults.dependencies?.loadBalancerNames!!)
            .contains(spec.overrides["us-east-1"]?.dependencies?.loadBalancerNames!!)
          that(usEastServerGroup.dependencies.securityGroupNames)
            .contains(spec.defaults.dependencies?.securityGroupNames!!)
            .contains(spec.overrides["us-east-1"]?.dependencies?.securityGroupNames!!)
        }
      }

      test("override health settings are merged with cluster-wide defaults") {
        expect {
          that(usEastServerGroup.health.warmup)
            .isEqualTo(spec.defaults.health?.warmup)
          that(usEastServerGroup.health.healthCheckType)
            .isEqualTo(spec.overrides["us-east-1"]?.health?.healthCheckType)
        }
      }

      derivedContext<Pair<ClusterSpec?, SubnetAwareLocations>>("a spec with location specified at the environment level") {
        deriveFixture {
          with(configuredTestObjectMapper().registerKeelEc2ApiModule()) {
            val tree = valueToTree<ObjectNode>(spec)
              .without<ObjectNode>("locations")
            val text = writeValueAsString(tree)
            copy().run {
              injectableValues = InjectableValues.Std(mapOf("locations" to spec.locations))
              readValue<ClusterSpec>(text)
            }
          } to spec.locations
        }

        test("locations from the surrounding context are applied to the spec") {
          expectThat(first)
            .isNotNull()
            .get(ClusterSpec::locations)
            .isEqualTo(second)
        }
      }
    }
  }
}

data class Fixture(
  val appVersion: String = "fnord-1.0.0",
  val baseImageName: String = "bionicbase-x86_64-202103092356-ebs",
  val usEastImageId: String = "ami-6874986",
  val usWestImageId: String = "ami-6271051",

  val spec: ClusterSpec = ClusterSpec(
    moniker = Moniker(
      app = "fnord",
      stack = "test"
    ),
    artifactReference = "fnord-deb",
    locations = SubnetAwareLocations(
      account = "test",
      vpc = "vpc0",
      subnet = "internal (vpc0)",
      regions = setOf(
        SubnetAwareRegionSpec(
          name = "us-east-1",
          availabilityZones = setOf("us-east-1c", "us-east-1d", "us-east-1e")
        ),
        SubnetAwareRegionSpec(
          name = "us-west-2",
          availabilityZones = setOf("us-west-2a", "us-west-2b", "us-west-2c")
        )
      )
    ),
    _defaults = ServerGroupSpec(
      launchConfiguration = LaunchConfigurationSpec(
        instanceType = "m5.large",
        ebsOptimized = true,
        iamRole = "fnordInstanceProfile",
        instanceMonitoring = false
      ),
      capacity = Capacity(2, 2, 2),
      dependencies = ClusterDependencies(
        loadBalancerNames = setOf("fnord-internal"),
        securityGroupNames = setOf("fnord", "fnord-elb")
      ),
      health = HealthSpec(
        warmup = Duration.ofSeconds(120)
      )
    ),
    overrides = mapOf(
      "us-east-1" to ServerGroupSpec(
        launchConfiguration = LaunchConfigurationSpec(
          image = VirtualMachineImage(
            appVersion = appVersion,
            baseImageName = baseImageName,
            id = usEastImageId
          ),
          iamRole = "fnordEastInstanceProfile",
          keyPair = "fnord-keypair-325719997469-us-east-1"
        ),
        capacity = Capacity(5, 5, 5),
        dependencies = ClusterDependencies(
          loadBalancerNames = setOf("fnord-external"),
          securityGroupNames = setOf("fnord-ext")
        ),
        health = HealthSpec(
          healthCheckType = ELB
        )
      ),
      "us-west-2" to ServerGroupSpec(
        launchConfiguration = LaunchConfigurationSpec(
          image = VirtualMachineImage(
            appVersion = appVersion,
            baseImageName = baseImageName,
            id = usWestImageId
          ),
          keyPair = "fnord-keypair-325719997469-us-west-2"
        )
      )
    )
  )
) {
  val usEastServerGroup
    get() = result.first { it.location.region == "us-east-1" }
  val usWestServerGroup
    get() = result.first { it.location.region == "us-west-2" }

  fun resolve(): Set<ServerGroup> = spec.resolve()

  val result: Set<ServerGroup> by lazy { resolve() }
}
