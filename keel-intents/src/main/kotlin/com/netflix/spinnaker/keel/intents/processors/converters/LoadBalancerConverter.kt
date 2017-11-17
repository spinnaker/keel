package com.netflix.spinnaker.keel.intents.processors.converters

import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.HealthCheck
import com.netflix.spinnaker.keel.clouddriver.model.ElasticLoadBalancer.ListenerDescription
import com.netflix.spinnaker.keel.intents.AmazonElasticLoadBalancerSpec
import com.netflix.spinnaker.keel.intents.AvailabilityZoneConfig.Automatic
import com.netflix.spinnaker.keel.intents.AvailabilityZoneConfig.Manual
import com.netflix.spinnaker.keel.intents.HealthCheckSpec
import com.netflix.spinnaker.keel.intents.HealthEndpoint
import com.netflix.spinnaker.keel.intents.LoadBalancerSpec
import com.netflix.spinnaker.keel.model.Job
import com.netflix.spinnaker.keel.model.Protocol
import com.netflix.spinnaker.keel.model.Protocol.*
import org.springframework.stereotype.Component

@Component
class LoadBalancerConverter(
  private val cloudDriver: CloudDriverCache
) : SpecConverter<LoadBalancerSpec, ElasticLoadBalancer> {
  override fun convertToState(spec: LoadBalancerSpec): ElasticLoadBalancer {
    if (spec is AmazonElasticLoadBalancerSpec) {
      val vpcId = cloudDriver.networkBy(spec.vpcName!!, spec.accountName, spec.region).id
      val zones = cloudDriver.availabilityZonesBy(spec.accountName, vpcId, spec.region)
      return ElasticLoadBalancer(
        loadBalancerName = spec.name,
        scheme = spec.scheme,
        vpcid = vpcId,
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
        securityGroups = spec.securityGroupNames
      )
    } else {
      TODO("${spec.javaClass.simpleName} is not supported")
    }
  }

  override fun convertFromState(state: ElasticLoadBalancer): LoadBalancerSpec =
    state.run {
      val vpc = cloudDriver.networkBy(vpcid!!)
      val zones = cloudDriver.availabilityZonesBy(vpc.account, vpc.id, vpc.region)
      AmazonElasticLoadBalancerSpec(
        cloudProvider = vpc.cloudProvider,
        accountName = vpc.account,
        region = vpc.region,
        vpcName = vpc.name,
        application = loadBalancerName.substringBefore("-"),
        name = loadBalancerName,
        healthCheck = healthCheck.convertFromState(),
        availabilityZones = if (availabilityZones == zones) Automatic else Manual(availabilityZones),
        scheme = scheme,
        listeners = listenerDescriptions.map { it.listener }.toSet(),
        securityGroupNames = securityGroups.map {
          cloudDriver.securityGroupBy(vpc.account, it).name
        }.toSet()
      )
    }

  override fun convertToJob(spec: LoadBalancerSpec): List<Job> {
    TODO("not implemented")
  }

  private fun HealthCheck.convertFromState(): HealthCheckSpec =
    HealthCheckSpec(
      target = Regex("([A-Z]+):(\\d+)(/\\w+)?").find(target)
        ?.let { match ->
          match.groupValues
            .let { Triple(Protocol.valueOf(it[1]), it[2].toInt(), it[3]) }
            .let { (protocol, port, path) ->
              when (protocol) {
                HTTP -> HealthEndpoint.Http(port, path)
                HTTPS -> HealthEndpoint.Https(port, path)
                SSL -> HealthEndpoint.Ssl(port)
                TCP -> HealthEndpoint.Tcp(port)
              }
            }
        } ?: throw IllegalStateException("Unable to parse health check target \"$target\""),
      timeout = timeout,
      interval = interval,
      healthyThreshold = healthyThreshold,
      unhealthyThreshold = unhealthyThreshold
    )
}
