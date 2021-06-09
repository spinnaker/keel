package com.netflix.spinnaker.keel.ec2.jackson.mixins

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY
import com.netflix.spinnaker.keel.api.Dependency
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec.ApplicationLoadBalancerOverride
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerType
import java.time.Duration

interface ApplicationLoadBalancerSpecMixin {
  @get:JsonInclude(NON_EMPTY)
  val overrides: Map<String, ApplicationLoadBalancerOverride>

  @get:JsonIgnore
  val maxDiffCount: Int?

  @get:JsonIgnore
  val unhappyWaitTime: Duration?

  @get:JsonIgnore
  val loadBalancerType: LoadBalancerType

  @get:JsonIgnore
  val id: String

  @get:JsonIgnore
  val dependsOn: Set<Dependency>
}
