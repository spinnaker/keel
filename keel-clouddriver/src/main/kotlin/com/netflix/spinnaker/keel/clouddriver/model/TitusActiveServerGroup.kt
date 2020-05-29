/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.clouddriver.model

import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.core.api.Capacity

data class TitusServerGroup(
  override val name: String,
  val awsAccount: String,
  val placement: Placement,
  override val region: String,
  val image: TitusActiveServerGroupImage,
  val iamProfile: String,
  val entryPoint: String,
  override val targetGroups: Set<String>,
  override val loadBalancers: Set<String>,
  override val securityGroups: Set<String>,
  override val capacity: Capacity,
  override val cloudProvider: String,
  override val moniker: Moniker,
  val env: Map<String, String>,
  val containerAttributes: Map<String, String> = emptyMap(),
  val migrationPolicy: MigrationPolicy,
  val serviceJobProcesses: ServiceJobProcesses,
  val constraints: Constraints,
  val tags: Map<String, String>,
  val resources: Resources,
  val capacityGroup: String,
  override val disabled: Boolean
) : BaseServerGroup

fun TitusServerGroup.toActive() =
  TitusActiveServerGroup(
    name = name,
    awsAccount = awsAccount,
    placement = placement,
    region = region,
    image = image,
    iamProfile = iamProfile,
    entryPoint = entryPoint,
    targetGroups = targetGroups,
    loadBalancers = loadBalancers,
    securityGroups = securityGroups,
    capacity = capacity,
    cloudProvider = cloudProvider,
    moniker = moniker,
    env = env,
    containerAttributes = containerAttributes,
    migrationPolicy = migrationPolicy,
    serviceJobProcesses = serviceJobProcesses,
    constraints = constraints,
    tags = tags,
    resources = resources,
    capacityGroup = capacityGroup)

data class TitusActiveServerGroup(
  override val name: String,
  val awsAccount: String,
  val placement: Placement,
  override val region: String,
  val image: TitusActiveServerGroupImage,
  val iamProfile: String,
  val entryPoint: String,
  override val targetGroups: Set<String>,
  override val loadBalancers: Set<String>,
  override val securityGroups: Set<String>,
  override val capacity: Capacity,
  override val cloudProvider: String,
  override val moniker: Moniker,
  val env: Map<String, String>,
  val containerAttributes: Map<String, String> = emptyMap(),
  val migrationPolicy: MigrationPolicy,
  val serviceJobProcesses: ServiceJobProcesses,
  val constraints: Constraints,
  val tags: Map<String, String>,
  val resources: Resources,
  val capacityGroup: String
) : BaseServerGroup

data class Placement(
  val account: String,
  val region: String,
  val zones: List<String> = emptyList()
)

data class MigrationPolicy(
  val type: String = "systemDefault"
)

data class TitusActiveServerGroupImage(
  val dockerImageName: String,
  val dockerImageVersion: String,
  val dockerImageDigest: String
)

data class Resources(
  val cpu: Int = 1,
  val disk: Int = 10000,
  val gpu: Int = 0,
  val memory: Int = 512,
  val networkMbps: Int = 128
)

data class Constraints(
  val hard: Map<String, Any> = emptyMap(),
  val soft: Map<String, Any> = mapOf("ZoneBalance" to "true")
)

data class ServiceJobProcesses(
  val disableIncreaseDesired: Boolean = false,
  val disableDecreaseDesired: Boolean = false
)
