package com.netflix.spinnaker.keel.api.ec2

import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.core.api.ClusterDependencies

/**
 * A [ResourceSpec] that has default and override cluster dependencies (i.e. security groups, load
 * balancers, and/or target groups).
 */
interface OverrideableClusterDependencyContainer<T : ClusterDependencyContainer> :
  Locatable<SubnetAwareLocations> {
  val defaults: T
  val overrides: Map<String, T>
}

interface ClusterDependencyContainer {
  val dependencies: ClusterDependencies?
}

/**
 * Resolves overrides and returns a map of security group name to the regions it is required in.
 * For example:
 * ```
 * fnord -> (us-east-1, us-west-2)
 * fnord-elb -> (us-east-1, us-west-2)
 * fnord-ext -> (us-east-1)
 * ```
 */
val OverrideableClusterDependencyContainer<*>.securityGroupsByRegion: Map<String, Set<String>>
  get() {
    val regions = locations.regions.map { it.name }.toSet()
    val defaultSecurityGroups = (defaults.dependencies?.securityGroupNames ?: emptySet())
      .associateWith { regions }
    val overrideSecurityGroups = overrides
      .map { (region, spec) ->
        spec.dependencies?.securityGroupNames?.associateWith { setOf(region) } ?: emptyMap()
      }
      .reduce { acc, map ->
        val result = mutableMapOf<String, Set<String>>()
        (acc.keys + map.keys).forEach {
          result[it] = (acc[it] ?: emptySet()) + (map[it] ?: emptySet())
        }
        result
      }
    return listOf(defaultSecurityGroups, overrideSecurityGroups)
      .reduce { acc, map ->
        val result = mutableMapOf<String, Set<String>>()
        (acc.keys + map.keys).forEach {
          result[it] = (acc[it] ?: emptySet()) + (map[it] ?: emptySet())
        }
        result
      }
  }
