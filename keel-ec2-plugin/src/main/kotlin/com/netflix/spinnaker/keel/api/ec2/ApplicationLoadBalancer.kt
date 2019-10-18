package com.netflix.spinnaker.keel.api.ec2

import com.netflix.spinnaker.keel.api.ec2.LoadBalancerType.APPLICATION
import com.netflix.spinnaker.keel.model.Moniker
import java.time.Duration

data class ApplicationLoadBalancer(
  val moniker: Moniker,
  val location: Location,
  val internal: Boolean = true,
  val dependencies: LoadBalancerDependencies = LoadBalancerDependencies(),
  val idleTimeout: Duration = Duration.ofSeconds(60),
  val listeners: Set<ApplicationLoadBalancerSpec.Listener>,
  val targetGroups: Set<ApplicationLoadBalancerSpec.TargetGroup>
) {
  val loadBalancerType: LoadBalancerType = APPLICATION
}
