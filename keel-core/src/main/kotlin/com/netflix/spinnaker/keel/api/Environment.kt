package com.netflix.spinnaker.keel.api

data class Environment(
  val name: String,
  val locations: SubnetAwareLocations? = null, // TODO: does it make sense to enforce subnet aware? I think so. Also long term we may want non-EC2 conceptions of locations
  val resources: Set<Resource<*>> = emptySet(),
  val constraints: Set<Constraint> = emptySet(),
  val notifications: Set<NotificationConfig> = emptySet() // applies to each resource
)

enum class PromotionStatus {
  PENDING, APPROVED, DEPLOYING, CURRENT, PREVIOUS
}
