package com.netflix.spinnaker.keel.api.ec2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerType.CLASSIC
import com.netflix.spinnaker.keel.core.parseMoniker
import java.time.Duration

data class ClassicLoadBalancerSpec(
  override val name: String,
  override val locations: SubnetAwareLocations,
  override val internal: Boolean = true,
  override val dependencies: LoadBalancerDependencies = LoadBalancerDependencies(),
  override val idleTimeout: Duration = Duration.ofSeconds(60),
  val listeners: Set<ClassicLoadBalancerListener> = emptySet(),
  val healthCheck: ClassicLoadBalancerHealthCheck,
  @JsonInclude(NON_EMPTY)
  val overrides: Map<String, ClassicLoadBalancerOverride> = emptyMap()
) : LoadBalancerSpec {

  init {
    require(name.length <= 32) {
      "load balancer names have a 32 character limit"
    }
  }

  override val moniker = parseMoniker(name)

  @JsonIgnore
  override val loadBalancerType: LoadBalancerType = CLASSIC

  @JsonIgnore
  override val id: String = "${locations.account}:$name"
}

data class ClassicLoadBalancerOverride(
  val dependencies: LoadBalancerDependencies? = null,
  val listeners: Set<ClassicLoadBalancerListener>? = null,
  val healthCheck: ClassicLoadBalancerHealthCheck? = null
)
