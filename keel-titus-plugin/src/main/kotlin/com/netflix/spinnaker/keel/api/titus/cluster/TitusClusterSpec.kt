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
package com.netflix.spinnaker.keel.api.titus.cluster

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.netflix.spinnaker.keel.api.Capacity
import com.netflix.spinnaker.keel.api.ClusterDependencies
import com.netflix.spinnaker.keel.api.ClusterDeployStrategy
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.Monikered
import com.netflix.spinnaker.keel.api.RedBlack
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.titus.exceptions.ErrorResolvingContainerException
import com.netflix.spinnaker.keel.clouddriver.model.Constraints
import com.netflix.spinnaker.keel.clouddriver.model.MigrationPolicy
import com.netflix.spinnaker.keel.clouddriver.model.Resources
import com.netflix.spinnaker.keel.docker.Container
import com.netflix.spinnaker.keel.docker.ContainerWithDigest
import com.netflix.spinnaker.keel.model.Moniker

/**
 * "Simplified" representation of
 * https://github.com/Netflix/titus-api-definitions/blob/master/src/main/proto/netflix/titus/titus_job_api.proto
 */
data class TitusClusterSpec(
  override val moniker: Moniker,
  val deployWith: ClusterDeployStrategy = RedBlack(),
  override val locations: SimpleLocations,
  private val _defaults: TitusServerGroupSpec,
  val overrides: Map<String, TitusServerGroupSpec> = emptyMap()
) : Monikered, Locatable<SimpleLocations> {

  @JsonIgnore
  override val id = "${locations.account}:${moniker.name}"

  val defaults: TitusServerGroupSpec
    @JsonUnwrapped get() = _defaults

  @JsonCreator
  constructor(
    moniker: Moniker,
    deployWith: ClusterDeployStrategy = RedBlack(),
    locations: SimpleLocations,
    container: Container,
    capacity: Capacity?,
    constraints: Constraints?,
    env: Map<String, String>?,
    containerAttributes: Map<String, String>?,
    resources: ResourcesSpec?,
    iamProfile: String?,
    entryPoint: String?,
    capacityGroup: String?,
    migrationPolicy: MigrationPolicy?,
    dependencies: ClusterDependencies?,
    tags: Map<String, String>?,
    overrides: Map<String, TitusServerGroupSpec> = emptyMap()
  ) : this(
    moniker,
    deployWith,
    locations,
    TitusServerGroupSpec(
      capacity = capacity,
      capacityGroup = capacityGroup,
      constraints = constraints,
      container = container,
      dependencies = dependencies,
      entryPoint = entryPoint,
      env = env,
      containerAttributes = containerAttributes,
      iamProfile = iamProfile,
      migrationPolicy = migrationPolicy,
      resources = resources,
      tags = tags
    ),
    overrides
  )
}

data class TitusServerGroupSpec(
  val container: Container,
  val capacity: Capacity? = null,
  val capacityGroup: String? = null,
  val constraints: Constraints? = null,
  val dependencies: ClusterDependencies? = null,
  val entryPoint: String? = null,
  val env: Map<String, String>? = null,
  val containerAttributes: Map<String, String>? = null,
  val iamProfile: String? = null,
  val migrationPolicy: MigrationPolicy? = null,
  val resources: ResourcesSpec? = null,
  val tags: Map<String, String>? = null
)

data class ResourcesSpec(
  val cpu: Int? = null,
  val disk: Int? = null,
  val gpu: Int? = null,
  val memory: Int? = null,
  val networkMbps: Int? = null
)

internal fun TitusClusterSpec.resolveCapacity(region: String) =
  overrides[region]?.capacity ?: defaults.capacity ?: Capacity(1, 1, 1)

internal fun TitusClusterSpec.resolveEnv(region: String) =
  emptyMap<String, String>() + overrides[region]?.env + defaults.env

internal fun TitusClusterSpec.resolveContainerAttributes(region: String) =
  emptyMap<String, String>() + overrides[region]?.containerAttributes + defaults.containerAttributes

internal fun TitusClusterSpec.resolveResources(region: String): Resources {
  val default by lazy { Resources() }
  return Resources(
    cpu = overrides[region]?.resources?.cpu ?: defaults.resources?.cpu ?: default.cpu,
    disk = overrides[region]?.resources?.disk ?: defaults.resources?.disk ?: default.disk,
    gpu = overrides[region]?.resources?.gpu ?: defaults.resources?.gpu ?: default.gpu,
    memory = overrides[region]?.resources?.memory ?: defaults.resources?.memory ?: default.memory,
    networkMbps = overrides[region]?.resources?.networkMbps ?: defaults.resources?.networkMbps ?: default.networkMbps
  )
}

internal fun TitusClusterSpec.resolveIamProfile(region: String) =
  overrides[region]?.iamProfile ?: defaults.iamProfile ?: moniker.app + "InstanceProfile"

internal fun TitusClusterSpec.resolveEntryPoint(region: String) =
  overrides[region]?.entryPoint ?: defaults.entryPoint ?: ""

internal fun TitusClusterSpec.resolveCapacityGroup(region: String) =
  overrides[region]?.capacityGroup ?: defaults.capacityGroup ?: moniker.app

internal fun TitusClusterSpec.resolveConstraints(region: String) =
  overrides[region]?.constraints ?: defaults.constraints ?: Constraints()

internal fun resolveContainer(container: Container): ContainerWithDigest {
  if (container is ContainerWithDigest) {
    return container
  } else {
    // The spec container should be replaced with a resolved container by now.
    // If not, something is wrong.
    throw ErrorResolvingContainerException(container)
  }
}

internal fun TitusClusterSpec.resolveMigrationPolicy(region: String) =
  overrides[region]?.migrationPolicy ?: defaults.migrationPolicy ?: MigrationPolicy()

internal fun TitusClusterSpec.resolveDependencies(region: String): ClusterDependencies =
  ClusterDependencies(
    loadBalancerNames = defaults.dependencies?.loadBalancerNames + overrides[region]?.dependencies?.loadBalancerNames,
    securityGroupNames = defaults.dependencies?.securityGroupNames + overrides[region]?.dependencies?.securityGroupNames,
    targetGroups = defaults.dependencies?.targetGroups + overrides[region]?.dependencies?.targetGroups
  )

fun TitusClusterSpec.resolve(): Set<TitusServerGroup> =
  locations.regions.map {
    TitusServerGroup(
      name = moniker.name,
      location = Location(
        account = locations.account,
        region = it.name
      ),
      capacity = resolveCapacity(it.name),
      capacityGroup = resolveCapacityGroup(it.name),
      constraints = resolveConstraints(it.name),
      container = resolveContainer(defaults.container),
      dependencies = resolveDependencies(it.name),
      entryPoint = resolveEntryPoint(it.name),
      env = resolveEnv(it.name),
      containerAttributes = resolveContainerAttributes(it.name),
      iamProfile = resolveIamProfile(it.name),
      migrationPolicy = resolveMigrationPolicy(it.name),
      resources = resolveResources(it.name),
      tags = defaults.tags + overrides[it.name]?.tags
    )
  }
    .toSet()

private operator fun <E> Set<E>?.plus(elements: Set<E>?): Set<E> =
  when {
    this == null || isEmpty() -> elements ?: emptySet()
    elements == null || elements.isEmpty() -> this
    else -> mutableSetOf<E>().also {
      it.addAll(this)
      it.addAll(elements)
    }
  }

private operator fun <K, V> Map<K, V>?.plus(map: Map<K, V>?): Map<K, V> =
  when {
    this == null || isEmpty() -> map ?: emptyMap()
    map == null || map.isEmpty() -> this
    else -> mutableMapOf<K, V>().also {
      it.putAll(this)
      it.putAll(map)
    }
  }
