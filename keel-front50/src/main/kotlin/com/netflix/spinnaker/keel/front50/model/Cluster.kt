package com.netflix.spinnaker.keel.front50.model

import com.netflix.spinnaker.keel.core.api.Capacity

data class Rollback(
  val onFailure: Boolean
)

enum class TerminationPolicy {
  Default, OldestInstance, NewestInstance, OldestLaunchConfiguration, ClosestToNextInstanceHour
}

data class Cluster(
  val account: String,
  val application: String,
  val availabilityZones: Map<String, Set<String>>,
  val capacity: Capacity,
  val cloudProvider: String = "aws",
  val cooldown: Long,
  val copySourceCustomBlockDeviceMappings: Boolean = false,
  val delayBeforeDisableSec: Long,
  val delayBeforeScaleDownSec: Long = 0,
  val ebsOptimized: Boolean = false,
  val healthCheckGracePeriod: Long,
  val healthCheckType: String,
  val iamRole: String,
  val instanceMonitoring: Boolean = false,
  val instanceType: String,
  val keyPair: String,
  val loadBalancers: List<Map<String, Any?>> = emptyList(),
  val maxRemainingAsgs: Int,
  val onFailure: Boolean,
  val preferSourceCapacity: Boolean = false,
  val provider: String = "aws",
  val rollback: Rollback? = null,
  val scaleDown: Boolean = true,
  val securityGroups: Set<String> = emptySet(),
  val stack: String,
  val strategy: String,
  val subnetType: String,
  val suspendedProcesses: Set<String> = emptySet(),
  val tags: Map<String, String> = emptyMap(),
  val targetGroups: List<Map<String, Any?>> = emptyList(),
  val targetHealthyDeployPercentage: Int,
  val terminationPolicies: Set<TerminationPolicy>,
  val useAmiBlockDeviceMappings: Boolean = false,
  val useSourceCapacity: Boolean = false
) {
  val moniker: ClusterMoniker
    get() = ClusterMoniker(application, stack)

  val region: String
    // The UI only supports one region per deploy
    get() = availabilityZones.keys.first()

  val name: String
    get() = if (stack.isNullOrEmpty()) account else stack
}
