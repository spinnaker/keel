package com.netflix.spinnaker.keel.intents.processors.converters

import com.netflix.spinnaker.keel.clouddriver.ClouddriverService
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.HealthCheck
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.ListenerDescription
import com.netflix.spinnaker.keel.intents.AmazonElasticLoadBalancerSpec
import com.netflix.spinnaker.keel.intents.AvailabilityZoneConfig
import com.netflix.spinnaker.keel.intents.LoadBalancerSpec
import com.netflix.spinnaker.keel.model.Job
import org.springframework.stereotype.Component

@Component
class LoadBalancerConverter(
  private val cloudDriver: ClouddriverService
) : SpecConverter<LoadBalancerSpec, ElasticLoadBalancer> {
  override fun convertToState(spec: LoadBalancerSpec): ElasticLoadBalancer {
    if (spec is AmazonElasticLoadBalancerSpec) {
      val vpcId = networkNameToId(cloudDriver.listNetworks(), spec.cloudProvider, spec.region, spec.vpcName)
      return ElasticLoadBalancer(
        loadBalancerName = spec.name,
        scheme = spec.scheme,
        availabilityZones = spec.availabilityZones.let { zoneSpec ->
          when (zoneSpec) {
            is AvailabilityZoneConfig.Manual -> zoneSpec.availabilityZones
            else -> cloudDriver
              .listSubnets(spec.cloudProvider)
              .filter {
                it.account == spec.accountName && it.region == spec.region && it.vpcId == vpcId
              }
              .map { it.availabilityZone }
              .toSet()
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
}
