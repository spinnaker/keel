/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.ec2.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.SPINNAKER_API_V1
import com.netflix.spinnaker.keel.api.ec2.CidrRule
import com.netflix.spinnaker.keel.api.ec2.CrossAccountReferenceRule
import com.netflix.spinnaker.keel.api.ec2.PortRange
import com.netflix.spinnaker.keel.api.ec2.ReferenceRule
import com.netflix.spinnaker.keel.api.ec2.SecurityGroup
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupSpec
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupRule
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupRule.Protocol
import com.netflix.spinnaker.keel.api.ec2.SelfReferenceRule
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.serviceAccount
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.SecurityGroupModel
import com.netflix.spinnaker.keel.diff.ResourceDiff
import com.netflix.spinnaker.keel.ec2.CLOUD_PROVIDER
import com.netflix.spinnaker.keel.events.Task
import com.netflix.spinnaker.keel.model.Job
import com.netflix.spinnaker.keel.model.Moniker
import com.netflix.spinnaker.keel.model.OrchestrationRequest
import com.netflix.spinnaker.keel.model.OrchestrationTrigger
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.plugin.ResourceHandler
import com.netflix.spinnaker.keel.plugin.ResourceNormalizer
import com.netflix.spinnaker.keel.retrofit.isNotFound
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import retrofit2.HttpException

class SecurityGroupHandler(
  private val cloudDriverService: CloudDriverService,
  private val cloudDriverCache: CloudDriverCache,
  private val orcaService: OrcaService,
  private val environmentResolver: EnvironmentResolver,
  override val objectMapper: ObjectMapper,
  override val normalizers: List<ResourceNormalizer<*>>
) : ResourceHandler<SecurityGroupSpec, Map<String, SecurityGroup>> {
  override val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

  override val apiVersion = SPINNAKER_API_V1.subApi("ec2")
  override val supportedKind = ResourceKind(
    apiVersion.group,
    "security-group",
    "security-groups"
  ) to SecurityGroupSpec::class.java

  override suspend fun desired(resource: Resource<SecurityGroupSpec>): Map<String, SecurityGroup> =
    with(resource.spec) {
      locations.regions.associateWith { region ->
        SecurityGroup(
          moniker = Moniker(app = moniker.app, stack = moniker.stack, detail = moniker.detail),
          location = SecurityGroup.Location(accountName = locations.accountName, region = region),
          vpcName = overrides[region]?.vpcName ?: vpcName,
          description = overrides[region]?.description ?: description,
          inboundRules = overrides[region]?.inboundRules ?: inboundRules
        )
      }
    }

  override suspend fun current(resource: Resource<SecurityGroupSpec>): Map<String, SecurityGroup> =
    cloudDriverService.getSecurityGroup(resource.spec, resource.serviceAccount)

  override suspend fun upsert(
    resource: Resource<SecurityGroupSpec>,
    resourceDiff: ResourceDiff<Map<String, SecurityGroup>>
  ): List<Task> =
    coroutineScope {
      resourceDiff
        .toIndividualDiffs()
        .filter { diff -> diff.hasChanges() }
        .map { diff ->
          val spec = diff.desired
          val job: Job
          val verb: Pair<String, String>

          when (diff.current) {
            null -> {
              job = spec.toCreateJob()
              verb = Pair("Creating", "create")
            }
            else -> {
              job = spec.toUpdateJob()
              verb = Pair("Updating", "update")
            }
          }

          log.info("${verb.first} security group using task: $job")

          val description = "${verb.first} security group ${spec.moniker.name} in " +
            "${spec.location.accountName}/${spec.location.region}"
          val notifications = environmentResolver.getNotificationsFor(resource.id)

          async {
            orcaService
              .orchestrate(
                resource.serviceAccount,
                OrchestrationRequest(
                  description,
                  spec.moniker.app,
                  description,
                  listOf(job),
                  OrchestrationTrigger("${resource.id}:${spec.location.region}", notifications = notifications)
                ))
              .let {
                log.info("Started task {} to ${verb.second} security group", it.ref)
                Task(id = it.taskId, name = description)
              }
          }
        }
        .map { it.await() }
    }

  override suspend fun delete(resource: Resource<SecurityGroupSpec>) {
    TODO("not implemented")
  }

  private fun ResourceDiff<Map<String, SecurityGroup>>.toIndividualDiffs() =
    desired.map { (region, desire) ->
      ResourceDiff(desire, current?.getOrDefault(region, null))
    }

  override suspend fun actuationInProgress(id: ResourceId) =
    orcaService
      .getCorrelatedExecutions(id.value)
      .isNotEmpty()

  private suspend fun CloudDriverService.getSecurityGroup(
    spec: SecurityGroupSpec,
    serviceAccount: String
  ): Map<String, SecurityGroup> =
    coroutineScope {
      spec.locations.regions.map { region ->
        async {
          try {
            getSecurityGroup(
              serviceAccount,
              spec.locations.accountName,
              CLOUD_PROVIDER,
              spec.moniker.name,
              region,
              spec.vpcName?.let { cloudDriverCache.networkBy(it, spec.locations.accountName, region).id }
            )
              .toSecurityGroup()
          } catch (e: HttpException) {
            if (e.isNotFound) {
              null
            } else {
              throw e
            }
          }
        }
      }
        .mapNotNull { it.await() }
        .associateBy { it.location.region }
    }

  private fun SecurityGroupModel.toSecurityGroup() =
    SecurityGroup(
      moniker = Moniker(app = moniker.app, stack = moniker.stack, detail = moniker.detail),
      location = SecurityGroup.Location(
        accountName,
        region = region
      ),
      vpcName = if (vpcId != null) {
        cloudDriverCache.networkBy(vpcId!!).name
      } else {
        null
      },
      description = description,
      inboundRules = inboundRules.flatMap { rule ->
        val ingressGroup = rule.securityGroup
        val ingressRange = rule.range
        val protocol = Protocol.valueOf(rule.protocol!!.toUpperCase())
        when {
          ingressGroup != null -> rule.portRanges
            ?.map { PortRange(it.startPort!!, it.endPort!!) }
            ?.map { portRange ->
              when {
                ingressGroup.accountName != accountName || ingressGroup.vpcId != vpcId -> CrossAccountReferenceRule(
                  protocol,
                  ingressGroup.name,
                  ingressGroup.accountName!!,
                  cloudDriverCache.networkBy(ingressGroup.vpcId!!).name!!,
                  portRange
                )
                ingressGroup.name != name -> ReferenceRule(
                  protocol,
                  ingressGroup.name,
                  portRange
                )
                else -> SelfReferenceRule(
                  protocol,
                  portRange
                )
              }
            } ?: emptyList()
          ingressRange != null -> rule.portRanges
            ?.map { PortRange(it.startPort!!, it.endPort!!) }
            ?.map { portRange ->
              CidrRule(
                protocol,
                portRange,
                ingressRange.ip + ingressRange.cidr
              )
            } ?: emptyList()
          else -> emptyList()
        }
      }
        .toSet()
    )

  private fun SecurityGroup.toCreateJob(): Job =
    Job(
      "upsertSecurityGroup",
      mapOf(
        "application" to moniker.app,
        "credentials" to location.accountName,
        "cloudProvider" to CLOUD_PROVIDER,
        "name" to moniker.name,
        "regions" to listOf(location.region),
        "vpcId" to cloudDriverCache.networkBy(vpcName, location.accountName, location.region).id,
        "description" to description,
        "securityGroupIngress" to inboundRules
          // we have to do a 2-phase create for self-referencing ingress rules as the referenced
          // security group must exist prior to the rule being applied. We filter then out here and
          // the subsequent diff will apply the additional group(s).
          .filterNot { it is SelfReferenceRule }
          .mapNotNull {
            it.referenceRuleToJob(this)
          },
        "ipIngress" to inboundRules.mapNotNull {
          it.cidrRuleToJob()
        },
        "accountName" to location.accountName
      )
    )

  private fun SecurityGroup.toUpdateJob(): Job =
    Job(
      "upsertSecurityGroup",
      mapOf(
        "application" to moniker.app,
        "credentials" to location.accountName,
        "cloudProvider" to CLOUD_PROVIDER,
        "name" to moniker.name,
        "regions" to listOf(location.region),
        "vpcId" to cloudDriverCache.networkBy(vpcName, location.accountName, location.region).id,
        "description" to description,
        "securityGroupIngress" to inboundRules.mapNotNull {
          it.referenceRuleToJob(this)
        },
        "ipIngress" to inboundRules.mapNotNull {
          it.cidrRuleToJob()
        },
        "accountName" to location.accountName
      )
    )

  private fun SecurityGroupRule.referenceRuleToJob(securityGroup: SecurityGroup): Map<String, Any?>? =
    when (this) {
      is ReferenceRule -> mapOf(
        "type" to protocol.name.toLowerCase(),
        "startPort" to portRange.startPort,
        "endPort" to portRange.endPort,
        "name" to name
      )
      is SelfReferenceRule -> mapOf(
        "type" to protocol.name.toLowerCase(),
        "startPort" to portRange.startPort,
        "endPort" to portRange.endPort,
        "name" to securityGroup.moniker.name
      )
      is CrossAccountReferenceRule -> mapOf(
        "type" to protocol.name.toLowerCase(),
        "startPort" to portRange.startPort,
        "endPort" to portRange.endPort,
        "name" to name,
        "accountName" to account,
        "crossAccountEnabled" to true,
        "vpcId" to cloudDriverCache.networkBy(
          vpcName,
          account,
          securityGroup.location.region
        ).id
      )
      else -> null
    }

  private fun SecurityGroupRule.cidrRuleToJob(): Map<String, Any?>? =
    when (this) {
      is CidrRule -> portRange.let { ports ->
        mapOf<String, Any?>(
          "type" to protocol.name,
          "startPort" to ports.startPort,
          "endPort" to ports.endPort,
          "cidr" to blockRange
        )
      }
      else -> null
    }
}
