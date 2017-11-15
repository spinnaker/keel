package com.netflix.spinnaker.keel.intents

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.netflix.spinnaker.config.KeelConfiguration
import com.netflix.spinnaker.config.KeelProperties
import com.netflix.spinnaker.hamkrest.shouldEqual
import com.netflix.spinnaker.keel.intents.AvailabilityZoneSpec.automatic
import com.netflix.spinnaker.keel.intents.HealthEndpoint.Http
import com.netflix.spinnaker.keel.model.Listener
import com.netflix.spinnaker.keel.model.Protocol.SSL
import com.netflix.spinnaker.keel.model.Protocol.TCP
import com.netflix.spinnaker.keel.model.Scheme.internal
import org.junit.jupiter.api.Test

object LoadBalancerIntentTest {

  val mapper = KeelConfiguration()
    .apply { properties = KeelProperties() }
    .objectMapper()

  @Test
  fun `can serialize to expected JSON format`() {
    val serialized = mapper.convertValue<Map<String, Any>>(elb)
    val deserialized = mapper.readValue<Map<String, Any>>(json)

    serialized shouldMatch equalTo(deserialized)
  }

  @Test
  fun `can deserialize from expected JSON format`() {
    mapper.readValue<LoadBalancerIntent>(json).apply {
      spec shouldEqual elb.spec
    }
  }

  val elb = LoadBalancerIntent(
    AmazonElasticLoadBalancerSpec(
      application = "covfefe",
      name = "covfefe-elb",
      cloudProvider = "aws",
      accountName = "mgmt",
      region = "us-west-2",
      securityGroupNames = setOf("covfefe", "nf-infrastructure", "nf-datacenter"),
      availabilityZones = automatic,
      scheme = internal,
      listeners = setOf(Listener(TCP, 80, TCP, 7001), Listener(SSL, 443, SSL, 7002)),
      healthCheck = HealthCheck(Http(7001, "/healthcheck")),
      vpcName = "vpcName"
    )
  )

  val json = """
{
  "kind": "LoadBalancer",
  "spec": {
    "kind": "aws",
    "application": "covfefe",
    "name": "covfefe-elb",
    "cloudProvider": "aws",
    "accountName": "mgmt",
    "vpcName": "vpcName",
    "region": "us-west-2",
    "securityGroupNames": [
      "covfefe",
      "nf-infrastructure",
      "nf-datacenter"
    ],
    "availabilityZones": "automatic",
    "scheme": "internal",
    "listeners": [
      {
        "protocol": "TCP",
        "loadBalancerPort": 80,
        "instanceProtocol": "TCP",
        "instancePort": 7001
      },
      {
        "protocol": "SSL",
        "loadBalancerPort": 443,
        "instanceProtocol": "SSL",
        "instancePort": 7002
      }
    ],
    "healthCheck": {
      "target": {
        "port": 7001,
        "path": "/healthcheck",
        "protocol": "HTTP"
      },
      "interval": 10,
      "timeout": 5,
      "unhealthyThreshold": 2,
      "healthyThreshold": 10
    }
  },
  "status": "ACTIVE",
  "labels": {},
  "attributes": [],
  "id": "LoadBalancer:aws:mgmt:covfefe-elb",
  "schema": "1"
}
"""


}
