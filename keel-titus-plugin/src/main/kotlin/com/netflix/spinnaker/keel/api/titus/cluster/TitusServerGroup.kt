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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.netflix.spinnaker.keel.api.ExcludedFromDiff
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.VersionedArtifactProvider
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DOCKER
import com.netflix.spinnaker.keel.api.ec2.Capacity
import com.netflix.spinnaker.keel.api.ec2.ClusterDependencies
import com.netflix.spinnaker.keel.api.ec2.ServerGroup.InstanceCounts
import com.netflix.spinnaker.keel.clouddriver.model.Constraints
import com.netflix.spinnaker.keel.clouddriver.model.MigrationPolicy
import com.netflix.spinnaker.keel.clouddriver.model.Resources
import com.netflix.spinnaker.keel.core.parseMoniker
import com.netflix.spinnaker.keel.docker.DigestProvider

data class TitusServerGroup(
  /**
   * This field is immutable, so we would never be reacting to a diff on it. If the name differs,
   * it's a different resource. Also, a server group name retrieved from CloudDriver will include
   * the sequence number. However, when we resolve desired state from a [ClusterSpec] this field
   * will _not_ include the sequence number. Having it on the model returned from CloudDriver is
   * useful for some things (e.g. specifying ancestor server group when red-blacking a new version)
   * but is meaningless for a diff.
   */
  @get:ExcludedFromDiff
  val name: String,
  val container: DigestProvider,
  val location: Location,
  val env: Map<String, String> = emptyMap(),
  val containerAttributes: Map<String, String> = emptyMap(),
  val resources: Resources = Resources(),
  val iamProfile: String,
  val entryPoint: String = "",
  val capacityGroup: String,
  val constraints: Constraints = Constraints(),
  val migrationPolicy: MigrationPolicy = MigrationPolicy(),
  val capacity: Capacity,
  val tags: Map<String, String> = emptyMap(),
  val dependencies: ClusterDependencies = ClusterDependencies(),
  val deferredInitialization: Boolean = true,
  val delayBeforeDisableSec: Int = 0,
  val delayBeforeScaleDownSec: Int = 0,
  val onlyEnabledServerGroup: Boolean = true,
  @JsonIgnore
  @get:ExcludedFromDiff
  override val artifactName: String? = null,
  @JsonIgnore
  @get:ExcludedFromDiff
  override val artifactType: ArtifactType? = DOCKER,
  @JsonIgnore
  @get:ExcludedFromDiff
  override val artifactVersion: String? = null,
  @JsonIgnore
  @get:ExcludedFromDiff
  val instanceCounts: InstanceCounts? = null
) : VersionedArtifactProvider

val TitusServerGroup.moniker: Moniker
  get() = parseMoniker(name)

fun Iterable<TitusServerGroup>.byRegion(): Map<String, TitusServerGroup> =
  associateBy { it.location.region }

// todo eb: should this be more general?
data class Location(
  val account: String,
  val region: String
)
