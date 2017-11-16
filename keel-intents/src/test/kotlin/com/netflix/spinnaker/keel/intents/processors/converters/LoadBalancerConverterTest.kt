package com.netflix.spinnaker.keel.intents.processors.converters

import com.netflix.spinnaker.hamkrest.shouldEqual
import com.netflix.spinnaker.keel.clouddriver.ClouddriverService
import com.netflix.spinnaker.keel.clouddriver.model.Network
import com.netflix.spinnaker.keel.clouddriver.model.Subnet
import com.netflix.spinnaker.keel.intents.AmazonElasticLoadBalancerSpec
import com.netflix.spinnaker.keel.intents.AvailabilityZoneConfig.Automatic
import com.netflix.spinnaker.keel.intents.HealthCheck
import com.netflix.spinnaker.keel.intents.HealthEndpoint.Http
import com.netflix.spinnaker.keel.model.Listener
import com.netflix.spinnaker.keel.model.Protocol.SSL
import com.netflix.spinnaker.keel.model.Protocol.TCP
import com.netflix.spinnaker.keel.model.Scheme.internal
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.Test

object LoadBalancerConverterTest {

  val cloudDriver = mock<ClouddriverService>()
  val converter = LoadBalancerConverter(cloudDriver)

  val spec = AmazonElasticLoadBalancerSpec(
    vpcName = "vpcName",
    application = "covfefe",
    name = "covfefe-elb",
    cloudProvider = "aws",
    accountName = "prod",
    region = "us-west-2",
    securityGroupNames = setOf("covfefe", "nf-infrastructure", "nf-datacenter"),
    availabilityZones = Automatic,
    scheme = internal,
    listeners = setOf(Listener(TCP, 80, TCP, 7001), Listener(SSL, 443, SSL, 7002)),
    healthCheck = HealthCheck(Http(7001, "/healthcheck"))
  )

  @Test
  fun `converts spec to system state`() {
    whenever(cloudDriver.listNetworks()) doReturn mapOf(
      spec.cloudProvider to setOf(
        Network(spec.cloudProvider, "vpc-1", spec.vpcName, spec.accountName, spec.region),
        Network(spec.cloudProvider, "vpc-2", spec.vpcName, "test", spec.region),
        Network(spec.cloudProvider, "vpc-3", spec.vpcName, spec.accountName, "us-east-1"),
        Network(spec.cloudProvider, "vpc-4", "otherName", spec.accountName, spec.region)
      )
    )

    whenever(cloudDriver.listSubnets(spec.cloudProvider)) doReturn setOf(
      Subnet("1", "vpc-1", spec.accountName, spec.region, "us-west-2a"),
      Subnet("2", "vpc-1", spec.accountName, spec.region, "us-west-2b"),
      Subnet("3", "vpc-1", spec.accountName, spec.region, "us-west-2c"),
      Subnet("4", "vpc-1", spec.accountName, "us-west-1", "us-west-1a"),
      Subnet("5", "vpc-1", spec.accountName, "us-west-1", "us-west-1b"),
      Subnet("6", "vpc-1", spec.accountName, "us-west-1", "us-west-1c"),
      Subnet("7", "vpc-2", "test", spec.region, "us-west-2a"),
      Subnet("8", "vpc-2", "test", spec.region, "us-west-2b"),
      Subnet("9", "vpc-2", "test", spec.region, "us-west-2c"),
      Subnet("a", "vpc-3", spec.accountName, spec.region, "us-west-2a"),
      Subnet("b", "vpc-3", spec.accountName, spec.region, "us-west-2b"),
      Subnet("c", "vpc-3", spec.accountName, spec.region, "us-west-2c")
    )

    converter.convertToState(spec)
      .let { elb ->
        elb.vpcid shouldEqual "vpc-1"
        elb.loadBalancerName shouldEqual spec.name
        elb.healthCheck.target shouldEqual spec.healthCheck.target.toString()
        elb.healthCheck.healthyThreshold shouldEqual spec.healthCheck.healthyThreshold
        elb.healthCheck.interval shouldEqual spec.healthCheck.interval
        elb.healthCheck.timeout shouldEqual spec.healthCheck.timeout
        elb.healthCheck.unhealthyThreshold shouldEqual spec.healthCheck.unhealthyThreshold
        elb.availabilityZones shouldEqual setOf("us-west-2a", "us-west-2b", "us-west-2c")
      }
  }
}
