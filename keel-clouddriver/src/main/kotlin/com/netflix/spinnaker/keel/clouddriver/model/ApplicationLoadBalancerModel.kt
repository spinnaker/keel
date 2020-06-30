package com.netflix.spinnaker.keel.clouddriver.model

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.ec2.Action
import com.netflix.spinnaker.keel.api.ec2.Rule
import com.netflix.spinnaker.keel.api.ec2.TargetGroupAttributes

data class ApplicationLoadBalancerModel(
  override val moniker: Moniker?,
  override val loadBalancerName: String,
  override val loadBalancerType: String = "application",
  override val availabilityZones: Set<String>,
  override val vpcId: String,
  override val subnets: Set<String>,
  override val scheme: String?,
  override val idleTimeout: Int,
  override val securityGroups: Set<String>,
  val listeners: List<ApplicationLoadBalancerListener>,
  val targetGroups: List<TargetGroup>,
  val ipAddressType: String,
  @get:JsonAnyGetter val properties: Map<String, Any?> = emptyMap()
) : AmazonLoadBalancer {
  data class ApplicationLoadBalancerListener(
    val port: Int,
    val protocol: String,
    val certificates: List<Certificate>?,
    val defaultActions: List<Action>,
    val rules: List<Rule>
  )

  data class Certificate(
    val certificateArn: String
  )

  data class TargetGroup(
    val targetGroupName: String,
    val loadBalancerNames: List<String>,
    val targetType: String,
    val matcher: TargetGroupMatcher,
    val protocol: String,
    val port: Int,
    val healthCheckEnabled: Boolean,
    val healthCheckTimeoutSeconds: Int,
    val healthCheckPort: String, // quoted number (e.g., "8080") or "traffic-port"
    val healthCheckProtocol: String,
    val healthCheckPath: String,
    val healthCheckIntervalSeconds: Int,
    val healthyThresholdCount: Int,
    val unhealthyThresholdCount: Int,
    val vpcId: String,
    val attributes: TargetGroupAttributes,
    @get:JsonAnyGetter val properties: Map<String, Any?> = emptyMap()
  )

  data class TargetGroupMatcher(
    val httpCode: String
  )
}
