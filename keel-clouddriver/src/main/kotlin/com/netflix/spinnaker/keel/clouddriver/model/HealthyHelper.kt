package com.netflix.spinnaker.keel.clouddriver.model

import com.netflix.spinnaker.keel.api.ClusterHealth

/**
 * Considers user defined health information to determine if a cluster is healthy or not
 */
fun isHealthy(clusterHealth: ClusterHealth?, instanceCounts: InstanceCounts?): Boolean {
  // if we have no information about cluster health, default to not healthy
  clusterHealth ?: return false
  // if we have no information about the instance health, default to not healthy
  instanceCounts ?: return false

  val healthyCount: Double = if (clusterHealth.ignoreHealthForDeployments) {
    // a deploy may not come up healthy, so use the total of up and and unknown
    // this happens when someone has checked "use cloud provider health"
    instanceCounts.unknown.toDouble() + instanceCounts.up.toDouble()
  } else {
    instanceCounts.up.toDouble()
  }

  return meetsHealthyThreshold(healthyCount, instanceCounts.total.toDouble(), clusterHealth.healthyPercentage)
}

/**
 * [percentageRequired] is a number between (0 and 100]
 */
fun meetsHealthyThreshold(healthyCount: Double, total: Double, percentageRequired: Double): Boolean =
  healthyCount >= total * (percentageRequired / 100.0)
