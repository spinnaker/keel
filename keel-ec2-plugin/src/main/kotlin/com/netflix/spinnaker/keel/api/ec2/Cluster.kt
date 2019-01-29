package com.netflix.spinnaker.keel.api.ec2

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
import com.netflix.spinnaker.keel.api.ec2.HealthCheckType.EC2
import com.netflix.spinnaker.keel.api.ec2.TerminationPolicy.OldestInstance
import java.time.Duration

@JsonInclude(NON_NULL)
data class Cluster(
  // what
  val application: String,
  val name: String,
  val packageName: String,

  // where
  val accountName: String,
  val regions: Set<String>,
  val availabilityZones: Map<String, Set<String>>?,
  val vpcName: String?,

  // instances
  val capacity: Capacity = Capacity(1, 1, 1),
  val instanceType: InstanceType,
  val ebsOptimized: Boolean,
  val ramdiskId: String? = null,
  val base64UserData: String? = null,

  // dependencies
  val loadBalancerNames: Set<String>,
  val securityGroupNames: Set<String>,
  val targetGroups: Set<String> = emptySet(),

  // health
  val instanceMonitoring: Boolean,
  val enabledMetrics: List<Metric>,
  val cooldown: Duration = Duration.ofSeconds(10),
  val healthCheckGracePeriod: Duration = Duration.ofSeconds(600),
  val healthCheckType: HealthCheckType = EC2,

  // auth
  val iamRole: String,
  val keyPair: String,

  // scaling
  val suspendedProcesses: Set<ScalingProcess> = emptySet(),
  val terminationPolicies: Set<TerminationPolicy> = setOf(OldestInstance),

  val legacyUdf: Boolean = false,

  // TODO: feels like these are really part of the deployment strategy rather than the cluster definition
  val copySourceCustomBlockDeviceMappings: Boolean,
  val useAmiBlockDeviceMappings: Boolean,
  val targetHealthyDeployPercentage: Int = 100
)

