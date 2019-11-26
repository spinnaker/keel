package com.netflix.spinnaker.keel.ec2.resolvers

import com.netflix.spinnaker.keel.api.Capacity
import com.netflix.spinnaker.keel.api.ClusterDependencies
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.SubnetAwareRegionSpec
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec
import com.netflix.spinnaker.keel.api.ec2.LaunchConfiguration
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.model.Network
import com.netflix.spinnaker.keel.clouddriver.model.SecurityGroupSummary
import com.netflix.spinnaker.keel.clouddriver.model.Subnet
import com.netflix.spinnaker.keel.ec2.CLOUD_PROVIDER
import com.netflix.spinnaker.keel.ec2.SPINNAKER_EC2_API_V1
import com.netflix.spinnaker.keel.model.Moniker
import com.netflix.spinnaker.keel.plugin.supporting
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isEqualTo

internal class LaunchConfigurationResolverTests : JUnit5Minutests {
  val vpcWest = Network(CLOUD_PROVIDER, "vpc-1452353", "vpc0", "test", "us-west-2")
  val vpcEast = Network(CLOUD_PROVIDER, "vpc-4342589", "vpc0", "test", "us-east-1")
  val sg1West = SecurityGroupSummary("keel", "sg-325234532", "vpc-1")
  val sg2West = SecurityGroupSummary("keel-elb", "sg-235425234", "vpc-1")
  val subnet1West = Subnet("subnet-1", vpcWest.id, vpcWest.account, vpcWest.region, "${vpcWest.region}a", "internal (vpc0)")
  val baseSpec = ClusterSpec(
    moniker = Moniker(app = "keel", stack = "test"),
    locations = SubnetAwareLocations(
      account = vpcWest.account,
      vpc = "vpc0",
      subnet = subnet1West.purpose!!,
      regions = listOf(vpcWest, vpcEast).map { subnet ->
        SubnetAwareRegionSpec(
          name = subnet.region,
          availabilityZones = listOf("a", "b", "c").map { "${subnet.region}$it" }.toSet()
        )
      }.toSet()
    ),
    _defaults = ClusterSpec.ServerGroupSpec(
      launchConfiguration = ClusterSpec.LaunchConfigurationSpec(
        image = ClusterSpec.VirtualMachineImage(
          id = "ami-123543254134",
          appVersion = "keel-0.287.0-h208.fe2e8a1",
          baseImageVersion = "nflx-base-5.308.0-h1044.b4b3f78"
        ),
        instanceType = "r4.8xlarge",
        ebsOptimized = false,
        iamRole = LaunchConfiguration.defaultIamRoleFor("keel"),
        keyPair = LaunchConfiguration.defaultKeyPairFor("test", "us-west-2"),
        instanceMonitoring = false
      ),
      capacity = Capacity(1, 6, 4),
      dependencies = ClusterDependencies(
        loadBalancerNames = setOf("keel-test-frontend"),
        securityGroupNames = setOf(sg1West.name, sg2West.name)
      )
    )
  )

  val cloudDriverCache = mockk<CloudDriverCache>()

  data class Fixture(val subject: LaunchConfigurationResolver, val spec: ClusterSpec) {
    val resource = resource(
      apiVersion = SPINNAKER_EC2_API_V1,
      kind = "cluster",
      spec = spec
    )
    val resolved by lazy { subject(resource) }
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture(
        LaunchConfigurationResolver(cloudDriverCache),
        baseSpec
      )
    }

    test("supports the resource kind") {
      expectThat(listOf(subject).supporting(resource))
        .containsExactly(subject)
    }

    context("non-templated default key pair configured in clouddriver") {
      before {
        with(cloudDriverCache) {
          every { defaultKeyPairForAccount("test") } returns "nf-test-keypair-a" // nf-keypair-test-{{region}}
        }
      }

      context("key pair specified in the spec defaults") {
        fixture {
          Fixture(
            LaunchConfigurationResolver(cloudDriverCache),
            baseSpec
          )
        }

        test("default is not touched in the spec") {
          expectThat(resolved.spec.defaults.launchConfiguration!!.keyPair)
            .isEqualTo(baseSpec.defaults.launchConfiguration!!.keyPair)
        }
      }

      context("no launch config in the spec defaults") {
        fixture {
          Fixture(
            LaunchConfigurationResolver(cloudDriverCache),
            baseSpec.withNoDefaultLaunchConfig()
          )
        }

        test("default is resolved in the spec") {
          expectThat(resolved.spec.defaults.launchConfiguration!!.keyPair)
            .isEqualTo("nf-test-keypair-a")
        }
      }
      context("no key pair in the spec defaults") {
        fixture {
          Fixture(
            LaunchConfigurationResolver(cloudDriverCache),
            baseSpec.withNoDefaultKeyPair()
          )
        }

        test("default is resolved in the spec") {
          expectThat(resolved.spec.defaults.launchConfiguration!!.keyPair)
            .isEqualTo("nf-test-keypair-a")
        }
      }
    }

    context("templated default key pair configured in clouddriver") {
      before {
        with(cloudDriverCache) {
          every { defaultKeyPairForAccount("test") } returns "nf-keypair-test-{{region}}"
        }
      }

      context("no launch configuration overrides in the spec") {
        fixture {
          Fixture(
            LaunchConfigurationResolver(cloudDriverCache),
            baseSpec
          )
        }

        test("key pair overrides are resolved in the spec") {
          expectThat(resolved.spec.overrides.size)
            .isEqualTo(2)
          expectThat(resolved.spec.overrides["us-west-2"]!!.launchConfiguration!!.keyPair)
            .isEqualTo("nf-keypair-test-us-west-2")
          expectThat(resolved.spec.overrides["us-east-1"]!!.launchConfiguration!!.keyPair)
            .isEqualTo("nf-keypair-test-us-east-1")
        }
      }

      context("some launch configuration overrides present in the spec") {
        fixture {
          Fixture(
            LaunchConfigurationResolver(cloudDriverCache),
            baseSpec.withKeyPairOverride("us-west-2")
          )
        }

        test("only missing key pair overrides are resolved in the spec") {
          expectThat(resolved.spec.overrides.size)
            .isEqualTo(2)
          expectThat(resolved.spec.overrides["us-west-2"]!!.launchConfiguration!!.keyPair)
            .isEqualTo("foobar")
          expectThat(resolved.spec.overrides["us-east-1"]!!.launchConfiguration!!.keyPair)
            .isEqualTo("nf-keypair-test-us-east-1")
        }
      }
    }
  }

  private fun ClusterSpec.withNoDefaultLaunchConfig() =
    copy(
      _defaults = defaults.copy(
        launchConfiguration = null
      )
    )

  private fun ClusterSpec.withNoDefaultKeyPair() =
    copy(
      _defaults = defaults.copy(
        launchConfiguration = defaults.launchConfiguration!!.copy(
          keyPair = null
        )
      )
    )

  private fun ClusterSpec.withKeyPairOverride(region: String) =
    copy(
      overrides = mapOf(
        region to ClusterSpec.ServerGroupSpec(
          launchConfiguration = ClusterSpec.LaunchConfigurationSpec(
            keyPair = "foobar"
          )
        )
      )
    )
}
