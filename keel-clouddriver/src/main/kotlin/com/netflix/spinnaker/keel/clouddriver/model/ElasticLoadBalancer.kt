package com.netflix.spinnaker.keel.clouddriver.model

import java.time.Instant

data class ElasticLoadBalancer(
  val loadBalancerName: String,
  val scheme: Scheme?,
  val vpcid: String?,
  val availabilityZones: Set<String>,
  val dnsname: String,
  val subnets: Set<String>,
  val securityGroups: Set<String>,
  val healthCheck: HealthCheck,
  val listenerDescriptions: Set<ListenerDescription>,
  val createdTime: Instant
) {
  enum class Scheme {
    internal, external
  }

  data class HealthCheck(
    val target: String,
    val interval: Int,
    val timeout: Int,
    val unhealthyThreshold: Int,
    val healthyThreshold: Int
  )

  data class ListenerDescription(
    val listener: Listener,
    val policyNames: Set<String>
  )

  data class Listener(
    val protocol: Protocol,
    val loadBalancerPort: Int,
    val instanceProtocol: Protocol,
    val instancePort: Int
  )

  enum class Protocol {
    HTTP, HTTPS, TCP, SSL
  }
}
