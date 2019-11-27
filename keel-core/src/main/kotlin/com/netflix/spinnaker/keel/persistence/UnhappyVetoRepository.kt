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
package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.ResourceId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import java.time.Clock
import java.time.Duration
import java.time.Instant

abstract class UnhappyVetoRepository(
  open val clock: Clock,
  @Value("veto.unhappy.waiting-time") var waitingTime: String = "PT10M"
) {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  abstract fun markUnhappy(resourceId: ResourceId, application: String)

  abstract fun markHappy(resourceId: ResourceId)

  abstract fun shouldSkip(resourceId: ResourceId): Boolean

  abstract fun getAll(): Set<ResourceId>

  /**
   * Get all resources for an application that this veto is currently vetoing
   */
  abstract fun getAllForApp(application: String): Set<ResourceId>

  fun calculateExpirationTime(): Instant =
    clock.instant().plus(Duration.parse(waitingTime))
}
