package com.netflix.spinnaker.keel.clouddriver.model

data class Cluster(
  val name: String,
  val accountName: String,
  val targetGroups: Collection<String>, // TODO: check type
  val serverGroups: Collection<ClusterServerGroup>
)

data class ClusterServerGroup(
  val name: String,
  val region: String,
  val zones: Collection<String>,
  val launchConfig: ClusterLaunchConfig,
  val asg: AutoScalingGroup,
  val vpcId: String,
  val targetGroups: Collection<String>,
  val loadBalancers: Collection<String>,
  val capacity: ClusterServerGroupCapacity,
  val securityGroups: Collection<String>
)

data class ClusterLaunchConfig(
  val ramdiskId: String,
  val ebsOptimized: Boolean,
  val imageId: String,
  val userData: String,
  val instanceType: String,
  val keyName: String,
  val iamInstanceProfile: String
)

data class AutoScalingGroup(
  val autoScalingGroupName: String,
  val defaultCooldown: Int,
  val healthCheckType: String,
  val healthCheckGracePeriod: Int,
  val tags: Collection<Tag>,
  val terminationPolicies: Collection<String>
)

data class Tag(
  val key: String,
  val value: String
)

data class ClusterServerGroupCapacity(
  val min: Int,
  val max: Int,
  val desired: Int
)
