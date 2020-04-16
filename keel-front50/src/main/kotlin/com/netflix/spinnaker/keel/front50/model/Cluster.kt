package com.netflix.spinnaker.keel.front50.model

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.As
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id
import com.netflix.spinnaker.keel.core.api.Capacity

data class Rollback(
  val onFailure: Boolean
)

enum class TerminationPolicy {
  Default, OldestInstance, NewestInstance, OldestLaunchConfiguration, ClosestToNextInstanceHour
}

@JsonTypeInfo(
  use = Id.NAME,
  include = As.EXISTING_PROPERTY,
  property = "cloudProvider"
)
@JsonSubTypes(
  Type(value = Ec2Cluster::class, name = "aws"),
  Type(value = TitusCluster::class, name = "titus")
)
abstract class Cluster(
  open val account: String,
  open val application: String,
  open val cloudProvider: String,
  open val stack: String? = null,
  val provider: String = cloudProvider
) {
//  val moniker: ClusterMoniker
//    get() = ClusterMoniker(application, stack)

  val name: String
    get() = application + if (stack.isNullOrEmpty()) "" else "-$stack"

  abstract val region: String
}

data class Ec2Cluster(
  override val account: String,
  override val application: String,
  val availabilityZones: Map<String, Set<String>>,
  val capacity: Capacity,
  override val cloudProvider: String = "aws",
  val cooldown: Long,
  val copySourceCustomBlockDeviceMappings: Boolean = false,
  val delayBeforeDisableSec: Long,
  val delayBeforeScaleDownSec: Long = 0,
  val ebsOptimized: Boolean = false,
  val healthCheckGracePeriod: Long,
  val healthCheckType: String = "EC2",
  val iamRole: String,
  val instanceMonitoring: Boolean = false,
  val instanceType: String,
  val keyPair: String,
  val loadBalancers: List<String> = emptyList(),
  val maxRemainingAsgs: Int,
  val onFailure: Boolean,
  val preferSourceCapacity: Boolean = false,
  val rollback: Rollback? = null,
  val scaleDown: Boolean = true,
  val securityGroups: Set<String> = emptySet(),
  override val stack: String? = null,
  val strategy: String,
  val subnetType: String = "internal (vpc0)",
  val suspendedProcesses: Set<String> = emptySet(),
  val tags: Map<String, String> = emptyMap(),
  val targetGroups: List<String> = emptyList(),
  val targetHealthyDeployPercentage: Int,
  val terminationPolicies: Set<TerminationPolicy>,
  val useAmiBlockDeviceMappings: Boolean = false,
  val useSourceCapacity: Boolean = false
) : Cluster(account, application, cloudProvider, stack) {
  override val region: String
    // The UI only supports one region per deploy
    get() = availabilityZones.keys.first()
}

data class TitusCluster(
  override val account: String,
  override val application: String,
  override val cloudProvider: String = "titus",
  override val stack: String? = null,
  override val region: String,
  @get:JsonAnyGetter
  val details: MutableMap<String, Any> = mutableMapOf()
) : Cluster(account, application, cloudProvider, stack) {
  @JsonAnySetter
  fun setAttribute(key: String, value: Any) {
    details[key] = value
  }
}
