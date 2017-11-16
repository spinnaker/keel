package com.netflix.spinnaker.keel.intents.processors.converters

import com.netflix.spinnaker.keel.clouddriver.ClouddriverService
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.HealthCheck
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.ListenerDescription
import com.netflix.spinnaker.keel.intents.AmazonElasticLoadBalancerSpec
import com.netflix.spinnaker.keel.intents.AvailabilityZoneConfig.Manual
import com.netflix.spinnaker.keel.intents.LoadBalancerSpec
import com.netflix.spinnaker.keel.model.Job
import org.springframework.stereotype.Component

@Component
class LoadBalancerConverter(
  private val cloudDriver: ClouddriverService
) : SpecConverter<LoadBalancerSpec, ElasticLoadBalancer> {
  override fun convertToState(spec: LoadBalancerSpec): ElasticLoadBalancer {
    if (spec is AmazonElasticLoadBalancerSpec) {
      val (vpcId, zones) = spec.fetchVpcAndZones()
      return ElasticLoadBalancer(
        loadBalancerName = spec.name,
        scheme = spec.scheme,
        availabilityZones = spec.availabilityZones.let { zoneConfig ->
          when (zoneConfig) {
            is Manual -> zoneConfig.availabilityZones
            else -> zones
          }
        },
        healthCheck = spec.healthCheck.run {
          HealthCheck(target.toString(), interval, timeout, unhealthyThreshold, healthyThreshold)
        },
        listenerDescriptions = spec.listeners.map { listener ->
          ListenerDescription(listener)
        }.toSet(),
        securityGroups = spec.securityGroupNames,
        vpcid = vpcId,
        dnsname = "TODO"
      )
    } else {
      TODO("not implemented")
    }
  }

  override fun convertFromState(state: ElasticLoadBalancer): LoadBalancerSpec? {
    TODO("not implemented")
  }

  override fun convertToJob(spec: LoadBalancerSpec): List<Job> {
    TODO("not implemented")
  }

  private fun AmazonElasticLoadBalancerSpec.fetchVpcAndZones(): Pair<String, Set<String>> =
    networkNameToId(cloudDriver.listNetworks(), cloudProvider, region, vpcName)
      ?.let { vpcId ->
        Pair(vpcId,
          cloudDriver
            .listSubnets(cloudProvider)
            .filter {
              it.account == accountName && it.region == region && it.vpcId == vpcId
            }
            .map { it.availabilityZone }
            .toSet())
      } ?: throw IllegalStateException("VPC name $vpcName is invalid")
}
