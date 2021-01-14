package com.netflix.spinnaker.keel.ec2.migrators

import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec.TargetGroup
import com.netflix.spinnaker.keel.api.ec2.EC2_APPLICATION_LOAD_BALANCER_V1_1
import com.netflix.spinnaker.keel.api.ec2.EC2_APPLICATION_LOAD_BALANCER_V1_2
import com.netflix.spinnaker.keel.api.ec2.old.ApplicationLoadBalancerV1_1Spec
import com.netflix.spinnaker.keel.resources.SpecMigrator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty("keel.plugins.ec2.enabled")
class ApplicationLoadBalancerV1_1ToV1_2Migrator :
  SpecMigrator<ApplicationLoadBalancerV1_1Spec, ApplicationLoadBalancerSpec> {
  @Suppress("DEPRECATION")
  override val input = EC2_APPLICATION_LOAD_BALANCER_V1_1
  override val output = EC2_APPLICATION_LOAD_BALANCER_V1_2

  override fun migrate(spec: ApplicationLoadBalancerV1_1Spec): ApplicationLoadBalancerSpec =
    ApplicationLoadBalancerSpec(
      moniker = spec.moniker,
      locations = spec.locations,
      internal = spec.internal,
      dependencies = spec.dependencies,
      idleTimeout = spec.idleTimeout,
      listeners = spec.listeners.mapTo(mutableSetOf()) {
        ApplicationLoadBalancerSpec.Listener(
          port = it.port,
          protocol = it.protocol,
          certificates = it.certificateArn?.let { certificateArn ->
            setOf(
              ApplicationLoadBalancerSpec.Certificate(
                certificateArn = certificateArn
              )
            )
          } ?: emptySet(),
          rules = it.rules,
          defaultActions = it.defaultActions
        )
      },
      targetGroups = spec.targetGroups.mapTo(mutableSetOf()) {
        TargetGroup(
          name = it.name,
          targetType = it.targetType,
          protocol = it.protocol,
          port = it.port,
          healthCheckEnabled = it.healthCheckEnabled,
          healthCheckTimeout = it.healthCheckTimeout,
          healthCheckPort = it.healthCheckPort,
          healthCheckProtocol = it.healthCheckProtocol,
          healthCheckHttpCode = it.healthCheckHttpCode,
          healthCheckPath = it.healthCheckPath,
          healthCheckInterval = it.healthCheckInterval,
          healthyThresholdCount = it.healthyThresholdCount,
          unhealthyThresholdCount = it.unhealthyThresholdCount,
          attributes = it.attributes
        )
      },
      overrides = spec.overrides
    )
}
