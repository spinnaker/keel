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
package com.netflix.spinnaker.keel.veto.unhappy

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.UnhappyControl
import com.netflix.spinnaker.keel.persistence.DiffFingerprintRepository
import com.netflix.spinnaker.keel.persistence.UnhappyVetoRepository
import com.netflix.spinnaker.keel.veto.Veto
import com.netflix.spinnaker.keel.veto.VetoResponse
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * A veto that stops keel from checking a resource for a configurable
 * amount of time so that we don't flap on a resource forever.
 */
@Component
class UnhappyVeto(
  private val diffFingerprintRepository: DiffFingerprintRepository,
  private val unhappyVetoRepository: UnhappyVetoRepository,
  private val dynamicConfigService: DynamicConfigService,
  @Value("\${veto.unhappy.waiting-time:PT10M}")
  private val configuredWaitingTime: String,
  private val clock: Clock
) : Veto {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override suspend fun check(resource: Resource<*>): VetoResponse {
    // maxActionCountPerDiff represents the number of times we're allowed to see a diff and take action to try and fix it.
    if (diffFingerprintRepository.actionTakenCount(resource.id) <= maxActionCountPerDiff(resource)) {
      unhappyVetoRepository.delete(resource)
      return allowedResponse()
    }

    val wait = waitingTime(resource)
    val recheckTime = unhappyVetoRepository.getRecheckTime(resource)

    /**
     * We deny the resource check if it's the first time we detected the resource being unhappy with this diff
     * (there's no record for it in the database), or if the recheck time has not expired yet. In the latter
     * case, we *don't* update the recheck time so that it will eventually expire and the resource re-checked.
     *
     * If the recheck time has expired, the resource remains marked unhappy in the database, but we allow it
     * to be rechecked and update the recheck time.
     */
    return if (recheckTime == null || recheckTime > clock.instant()) {
      if (recheckTime == null) {
        unhappyVetoRepository.markUnhappy(resource, calculateRecheckTime(wait))
      }
      log.debug("Resource ${resource.id} is unhappy. Denying resource check.")
      deniedResponse(unhappyMessage(resource))
    } else {
      log.debug("Marking resource ${resource.id} unhappy for $wait, but allowing resource check.")
      unhappyVetoRepository.markUnhappy(resource, calculateRecheckTime(wait))
      allowedResponse()
    }
  }

  override fun currentRejections(): List<String> =
    unhappyVetoRepository.getAll().toList()

  override fun currentRejectionsByApp(application: String) =
    unhappyVetoRepository.getAllForApp(application).toList()

  private fun maxActionCountPerDiff(resource: Resource<*>) =
    when (resource.spec) {
      is UnhappyControl -> (resource.spec as UnhappyControl).maxDiffCount ?: maxDiffCount
      else -> maxDiffCount
    }

  private val maxDiffCount: Int
    get() = dynamicConfigService.getConfig(
      Int::class.java,
      "veto.unhappy.max-diff-count",
      5
    )

  private fun waitingTime(resource: Resource<*>) =
    when (resource.spec) {
      is UnhappyControl -> (resource.spec as UnhappyControl).unhappyWaitTime ?: waitingTime
      else -> waitingTime
    }

  private val waitingTime: Duration
    get() = try {
      Duration.parse(
        dynamicConfigService.getConfig(
          String::class.java,
          "veto.unhappy.waiting-time",
          configuredWaitingTime
        )
      )
    } catch (e: DateTimeParseException) {
      log.error("'{}' is not a valid Duration", e.parsedString)
      throw e
    }

  private fun unhappyMessage(resource: Resource<*>): String {
    val maxDiffs = maxActionCountPerDiff(resource)
    val waitingTime = waitingTime(resource)
    if (waitingTime == Duration.ZERO) {
      return "Resource is unhappy and our $maxDiffs actions have not fixed it. " +
        "Resource will remain paused until the diff changes or the resource is manually unpaused."
    }
    return "Resource is unhappy and our $maxDiffs actions have not fixed it. We will try again after " +
      "$waitingTime, or if the diff changes."
  }

  fun calculateRecheckTime(wait: Duration?): Instant? =
    wait?.let { clock.instant().plus(it) }
}
