package com.netflix.spinnaker.keel.api.ec2.old

import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.UnhappyControl
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec.ApplicationLoadBalancerOverride
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec.Listener
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerDependencies
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerType
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerType.APPLICATION
import com.netflix.spinnaker.keel.api.ec2.TargetGroupAttributes
import com.netflix.spinnaker.keel.api.schema.Optional
import java.time.Duration

data class ApplicationLoadBalancerV1Spec(
  override val moniker: Moniker,
  @Optional override val locations: SubnetAwareLocations,
  override val internal: Boolean = true,
  override val dependencies: LoadBalancerDependencies = LoadBalancerDependencies(),
  override val idleTimeout: Duration = Duration.ofSeconds(60),
  val listeners: Set<Listener>,
  val targetGroups: Set<TargetGroupV1>,
  val overrides: Map<String, ApplicationLoadBalancerOverride> = emptyMap()
) : LoadBalancerSpec, UnhappyControl {

  init {
    require(moniker.toString().length <= 32) {
      "load balancer names have a 32 character limit"
    }
  }

  override val maxDiffCount: Int? = 2

  // Once load balancers go unhappy, only retry when the diff changes, or if manually unvetoed
  override val unhappyWaitTime: Duration? = null

  override val loadBalancerType: LoadBalancerType = APPLICATION

  override val id: String = "${locations.account}:$moniker"

  data class TargetGroupV1(
    val name: String,
    val targetType: String = "instance",
    val protocol: String = "HTTP",
    val port: Int,
    val healthCheckEnabled: Boolean = true,
    val healthCheckTimeoutSeconds: Duration = Duration.ofSeconds(5),
    val healthCheckPort: Int = 7001,
    val healthCheckProtocol: String = "HTTP",
    val healthCheckHttpCode: String = "200-299",
    val healthCheckPath: String = "/healthcheck",
    val healthCheckIntervalSeconds: Duration = Duration.ofSeconds(10),
    val healthyThresholdCount: Int = 10,
    val unhealthyThresholdCount: Int = 2,
    val attributes: TargetGroupAttributes = TargetGroupAttributes()
  ) {
    init {
      require(name.length <= 32) {
        "targetGroup names have a 32 character limit"
      }
    }
  }
}
