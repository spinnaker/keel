package com.netflix.spinnaker.keel.ec2.vetos

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.OverrideableClusterDependencyContainer
import com.netflix.spinnaker.keel.api.ec2.securityGroupsByRegion
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.core.api.DEFAULT_SERVICE_ACCOUNT
import com.netflix.spinnaker.keel.ec2.CLOUD_PROVIDER
import com.netflix.spinnaker.keel.veto.Veto
import com.netflix.spinnaker.keel.veto.VetoResponse
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import retrofit2.HttpException

@Component
class RequiredSecurityGroupVeto(
  private val cloudDriver: CloudDriverService,
  private val cloudDriverCache: CloudDriverCache
) : Veto {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override suspend fun check(resource: Resource<*>) =
    resource.spec.toSecurityGroupDependencies()
      ?.let {
        val missingSecurityGroupRegions = checkSecurityGroups(it)
        VetoResponse(
          allowed = missingSecurityGroupRegions.isEmpty(),
          vetoName = name(),
          message = missingSecurityGroupRegions.entries.joinToString(separator = "\n") { (securityGroup, missingRegions) ->
            "Security group $securityGroup is not found in ${missingRegions.joinToString()}"
          }
        )
      } ?: VetoResponse(true, name())

  private suspend fun checkSecurityGroups(spec: SecurityGroupDependencies): Map<String, List<String>> {
    val securityGroupMissingRegions = mutableMapOf<String, MutableList<String>>()
    // TODO: need to await the completion of coroutines
    spec.securityGroupsInRegions.forEach { (securityGroupName, regions) ->
      regions.forEach { region ->
        supervisorScope {
          launch {
            if (!securityGroupExists(spec.account, spec.subnet, region, securityGroupName)) {
              with(securityGroupMissingRegions) {
                putIfAbsent(securityGroupName, mutableListOf())
                getValue(securityGroupName).add(region)
              }
            }
          }
        }
      }
    }
    return securityGroupMissingRegions
  }

  private suspend fun securityGroupExists(
    account: String,
    vpcName: String?,
    region: String,
    name: String
  ): Boolean =
    runCatching {
      cloudDriver.getSecurityGroup(
        DEFAULT_SERVICE_ACCOUNT,
        account,
        CLOUD_PROVIDER,
        name,
        region,
        if (vpcName == null) null else cloudDriverCache.networkBy(vpcName, account, region).id
      )
    }
      .onFailure { ex ->
        if (ex is HttpException && ex.code() == 404) {
          log.warn("security group $name not found in $account/$vpcName/$region")
        } else {
          log.error("error finding security group $name in $account/$vpcName/$region", ex)
        }
      }
      .isSuccess

  data class SecurityGroupDependencies(
    val securityGroupsInRegions: Map<String, Collection<String>>,
    val account: String,
    val subnet: String?
  )

  private fun ResourceSpec.toSecurityGroupDependencies() =
    when (this) {
      is LoadBalancerSpec ->
        SecurityGroupDependencies(
          securityGroupsInRegions = dependencies.securityGroupNames.associateWith {
            locations.regions.map { it.name }
          },
          account = locations.account,
          subnet = locations.subnet
        )
      is OverrideableClusterDependencyContainer<*> -> {
        SecurityGroupDependencies(
          securityGroupsInRegions = securityGroupsByRegion,
          account = locations.account,
          subnet = locations.subnet
        )
      }
      else -> null
    }
}
