package com.netflix.spinnaker.keel.preview

import com.netflix.spinnaker.keel.api.Dependency
import com.netflix.spinnaker.keel.api.DependencyType
import com.netflix.spinnaker.keel.api.DependencyType.LOAD_BALANCER
import com.netflix.spinnaker.keel.api.DependencyType.SECURITY_GROUP
import com.netflix.spinnaker.keel.api.DependencyType.TARGET_GROUP
import com.netflix.spinnaker.keel.api.Dependent
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec.ApplicationLoadBalancerOverride
import com.netflix.spinnaker.keel.api.ec2.ClassicLoadBalancerOverride
import com.netflix.spinnaker.keel.api.ec2.ClassicLoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.ClusterDependencies
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerDependencies
import com.netflix.spinnaker.keel.api.titus.TitusClusterSpec
import com.netflix.spinnaker.keel.core.name
import org.apache.commons.codec.digest.DigestUtils
import kotlin.reflect.KClass
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberFunctions

internal fun <T : ResourceSpec> T.withDependencies(specClass: KClass<out T>, dependencies: Set<Dependency>): T {
  if (this !is Dependent) {
    return this
  }
  return when (this) {
    // need to call the overrides on the specific sub-types here as they're extension functions
    is ClusterSpec -> withDependencies(dependencies) as T
    is TitusClusterSpec -> withDependencies(dependencies) as T
    is ClassicLoadBalancerSpec -> withDependencies(dependencies) as T
    is ApplicationLoadBalancerSpec -> withDependencies(dependencies) as T
    // otherwise, we attempt to copy the spec with dependencies updated if it's a data class
    else -> {
      val copy = specClass.memberFunctions.first { it.name == "copy" }
      val instanceParam = copy?.instanceParameter
      val dependsOnParam = copy?.parameters.first { it.name == "dependsOn" }
      return if (copy != null && instanceParam != null && dependsOnParam != null) {
        copy.callBy(mapOf(instanceParam to this, dependsOnParam to dependencies)) as T
      } else {
        this
      }
    }
  }
}

private fun ClusterSpec.withDependencies(deps: Set<Dependency>): ClusterSpec {
  val commonDeps = deps.commonInAllRegions(locations.regions.map { it.name })
  val overrideDeps = deps.groupBy { it.region }
    .mapValues { (key, deps) -> deps - commonDeps }

  return copy(
    _defaults = defaults.copy(
      dependencies = defaults.dependencies?.copy(
        loadBalancerNames = commonDeps.namesForType(LOAD_BALANCER),
        securityGroupNames = commonDeps.namesForType(SECURITY_GROUP),
        targetGroups = commonDeps.namesForType(TARGET_GROUP)
      )
    ),
    overrides = overrides.mapValues { (region, serverGroupSpec) ->
      serverGroupSpec.copy(
        dependencies = ClusterDependencies(
          loadBalancerNames =  overrideDeps[region].namesForType(LOAD_BALANCER),
          securityGroupNames = overrideDeps[region].namesForType(SECURITY_GROUP),
          targetGroups = overrideDeps[region].namesForType(TARGET_GROUP)
        )
      )
    }
  )
}

private fun TitusClusterSpec.withDependencies(deps: Set<Dependency>): TitusClusterSpec {
  val commonDeps = deps.commonInAllRegions(locations.regions.map { it.name })
  val overrideDeps = deps.groupBy { it.region }
    .mapValues { (key, deps) -> deps - commonDeps }

  return copy(
    _defaults = defaults.copy(
      dependencies = defaults.dependencies?.copy(
        loadBalancerNames = commonDeps.namesForType(LOAD_BALANCER),
        securityGroupNames = commonDeps.namesForType(SECURITY_GROUP),
        targetGroups = commonDeps.namesForType(TARGET_GROUP)
      )
    ),
    overrides = overrides.mapValues { (region, serverGroupSpec) ->
      serverGroupSpec.copy(
        dependencies = ClusterDependencies(
          loadBalancerNames =  overrideDeps[region].namesForType(LOAD_BALANCER),
          securityGroupNames = overrideDeps[region].namesForType(SECURITY_GROUP),
          targetGroups = overrideDeps[region].namesForType(TARGET_GROUP)
        )
      )
    }
  )
}

fun ClassicLoadBalancerSpec.withDependencies(deps: Set<Dependency>): ClassicLoadBalancerSpec {
  val commonDeps = deps.commonInAllRegions(locations.regions.map { it.name })
  val overrideDeps = deps.groupBy { it.region }
    .mapValues { (key, deps) -> deps - commonDeps }

  return copy(
    dependencies = LoadBalancerDependencies(
      securityGroupNames = commonDeps.namesForType(SECURITY_GROUP)
    ),
    overrides = overrides.mapValues { (region, override) ->
      val secGroupOverrides = overrideDeps[region].namesForType(SECURITY_GROUP)
      if (secGroupOverrides.isNotEmpty()) {
        override.copy(
          dependencies = LoadBalancerDependencies(
            securityGroupNames = secGroupOverrides
          )
        )
      } else {
        override
      }
    }.toMutableMap().apply {
      // for the case where the region was previously not present in the overrides
      overrideDeps.forEach { (region, deps) ->
        val secGroupOverrides = overrideDeps[region].namesForType(SECURITY_GROUP)
        if (secGroupOverrides.isNotEmpty()) {
          putIfAbsent(region, ClassicLoadBalancerOverride(
            dependencies = LoadBalancerDependencies(
              securityGroupNames = secGroupOverrides
            )
          ))
        }
      }
    }
  )
}

fun ApplicationLoadBalancerSpec.withDependencies(deps: Set<Dependency>): ApplicationLoadBalancerSpec {
  val commonDeps = deps.commonInAllRegions(locations.regions.map { it.name })
  val overrideDeps = deps.groupBy { it.region }
    .mapValues { (key, deps) -> deps - commonDeps }

  return copy(
    dependencies = LoadBalancerDependencies(
      securityGroupNames = commonDeps.namesForType(SECURITY_GROUP)
    ),
    overrides = overrides.mapValues { (region, override) ->
      val secGroupOverrides = overrideDeps[region].namesForType(SECURITY_GROUP)
      if (secGroupOverrides.isNotEmpty()) {
        override.copy(
          dependencies = LoadBalancerDependencies(
            securityGroupNames = secGroupOverrides
          )
        )
      } else {
        override
      }
    }.toMutableMap().apply {
      // for the case where the region was previously not present in the overrides
      overrideDeps.forEach { (region, deps) ->
        val secGroupOverrides = overrideDeps[region].namesForType(SECURITY_GROUP)
        if (secGroupOverrides.isNotEmpty()) {
          putIfAbsent(region, ApplicationLoadBalancerOverride(
            dependencies = LoadBalancerDependencies(
              securityGroupNames = secGroupOverrides
            )
          ))
        }
      }
    }
  )
}

private fun Set<Dependency>.commonInAllRegions(regions: List<String>): Set<Dependency> =
  groupBy { Pair(it.type, it.name) }
    .filter { (_, deps) -> deps.map { it.region } == regions }
    .flatMap { it.value }
    .toSet()

private fun Collection<Dependency>?.namesForType(type: DependencyType): Set<String> =
  if (this == null) {
    emptySet()
  } else {
    filter { it.type == type }.map { it.name }.toSet()
  }

/**
 * Use with branch names to normalize them for use in environment and resource names.
 */
internal fun String.toPreviewName() = substringAfterLast('/')

internal const val MAX_RESOURCE_NAME_LENGTH = 32

/**
 * @return The [Moniker] with an updated [Moniker.detail] field containing as much of the branch name
 * as possible while respecting max length constraints on resource names.
 */
internal fun Moniker.withBranchDetail(branch: String): Moniker {
  val normalizedBranch = branch.toPreviewName()
  val suffix = DigestUtils.sha256Hex(normalizedBranch).takeLast(4)
  val truncateAt = MAX_RESOURCE_NAME_LENGTH - name.length + (detail?.length ?: 0) - suffix.length - 1
  val updatedDetail = listOfNotNull(detail, normalizedBranch).joinToString("-").let {
    // truncate to accommodate AWS naming restrictions
    // e.g. myapp-test-mycluster (20 chars) with branch my-cool-feature (15 chars)
    // would be renamed to something like myapp-test-mycluster-my-coo-1a2b
    it.take(truncateAt) + "-$suffix"
  }
  return copy(detail = updatedDetail)
}
