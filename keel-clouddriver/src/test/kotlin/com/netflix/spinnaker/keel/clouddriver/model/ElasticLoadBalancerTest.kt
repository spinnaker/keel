package com.netflix.spinnaker.keel.clouddriver.model

import com.netflix.spinnaker.keel.clouddriver.ClouddriverService
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.*
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.Protocol.HTTP
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.Protocol.TCP
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.Scheme.internal
import java.net.URL
import java.time.Instant

object ElasticLoadBalancerTest : BaseModelParsingTest<ElasticLoadBalancer>() {

  override val json: URL
    get() = javaClass.getResource("/elb.json")

  override val call: ClouddriverService.() -> ElasticLoadBalancer?
    get() = {
      getElasticLoadBalancer(
        "aws",
        "mgmt",
        "us-west-2",
        "covfefe-main-vpc0"
      ).firstOrNull()
    }

  override val expected: ElasticLoadBalancer
    get() = ElasticLoadBalancer(
      loadBalancerName = "covfefe-test-vpc0",
      scheme = internal,
      vpcid = "vpc-ljycv6ep",
      availabilityZones = setOf("us-west-2a", "us-west-2b", "us-west-2c"),
      dnsname = "internal-covfefe-test-vpc0-991611405.us-west-2.elb.amazonaws.com",
      subnets = setOf("subnet-rb5qsr6n", "subnet-obmsqtr1", "subnet-19fdc8li"),
      securityGroups = setOf("sg-skerlbt5", "sg-epos7i16", "sg-feuxpxqk", "sg-k6cc85a1"),
      healthCheck = HealthCheck(
        target = "HTTP:7001/health",
        interval = 10,
        timeout = 5,
        unhealthyThreshold = 2,
        healthyThreshold = 10
      ),
      listenerDescriptions = setOf(
        ListenerDescription(
          listener = Listener(
            protocol = HTTP,
            loadBalancerPort = 80,
            instanceProtocol = HTTP,
            instancePort = 7001
          ),
          policyNames = emptySet())
        ,
        ListenerDescription(
          listener = Listener(
            protocol = TCP,
            loadBalancerPort = 443,
            instanceProtocol = TCP,
            instancePort = 7002
          ),
          policyNames = emptySet())
      ),
      createdTime = Instant.ofEpochMilli(1488575522770L)
    )
}
