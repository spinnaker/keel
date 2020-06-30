package com.netflix.spinnaker.keel.api.ec2

data class InstanceCounts(
  val total: Int,
  val up: Int,
  val down: Int,
  val unknown: Int,
  val outOfService: Int,
  val starting: Int
) {
  // active asg is healthy if all instances are up
  fun isHealthy(): Boolean =
    up == total
}
