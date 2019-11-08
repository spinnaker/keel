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
package com.netflix.spinnaker.keel.api.ec2

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY
import java.time.Duration

@JsonInclude(NON_EMPTY)
data class Health(
  val cooldown: Duration = Duration.ofSeconds(10),
  val warmup: Duration = Duration.ofSeconds(600),
  val healthCheckType: HealthCheckType = HealthCheckType.EC2,
  val enabledMetrics: Set<Metric> = emptySet(),
  // Note: the default for this in Deck is currently setOf(TerminationPolicy.Default), but we were advised by Netflix
  // SRE to change the default to OldestInstance
  val terminationPolicies: Set<TerminationPolicy> = setOf(TerminationPolicy.OldestInstance)
) {
  fun toClusterHealthSpec(omitDefaults: Boolean = false) =
    ClusterSpec.HealthSpec(
      if (omitDefaults && cooldown == defaults.cooldown) {
        null
      } else {
        cooldown
      },
      if (omitDefaults && warmup == defaults.warmup) {
        null
      } else {
        warmup
      },
      if (omitDefaults && healthCheckType == defaults.healthCheckType) {
        null
      } else {
        healthCheckType
      },
      if (omitDefaults && enabledMetrics == defaults.enabledMetrics) {
        null
      } else {
        enabledMetrics
      },
      if (omitDefaults && terminationPolicies == defaults.terminationPolicies) {
        null
      } else {
        terminationPolicies
      }
    )

  companion object {
    val defaults by lazy { Health() }
  }
}
