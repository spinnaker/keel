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
package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.pause.ResourcePauser
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.ResourceStatus.PAUSED
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/application"])
class ApplicationController(
  private val resourceRepository: ResourceRepository,
  private val deliveryConfigRepository: DeliveryConfigRepository,
  private val resourcePauser: ResourcePauser
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @GetMapping(
    path = ["/{application}"],
    produces = [APPLICATION_JSON_VALUE]
  )
  fun get(
    @PathVariable("application") application: String,
    @RequestParam("includeDetails", required = false, defaultValue = "false") includeDetails: Boolean
  ): Map<String, Any> {
    val hasDeliveryConfig = deliveryConfigRepository.hasDeliveryConfig(application)

    if (includeDetails) {
      var resources = resourceRepository.getSummaryByApplication(application)
      if (resourcePauser.applicationIsPaused(application)) {
        resources = resources.map { it.copy(status = PAUSED) }
      }
      val constraintStates = if (hasDeliveryConfig) {
        deliveryConfigRepository.constraintStateFor(application)
      } else {
        emptyList()
      }

      return mapOf(
        "hasManagedResources" to resources.isNotEmpty(),
        "resources" to resources,
        "hasDeliveryConfig" to hasDeliveryConfig,
        "currentEnvironmentConstraints" to constraintStates
      )
    }
    return mapOf(
      "hasManagedResources" to resourceRepository.hasManagedResources(application),
      "hasDeliveryConfig" to hasDeliveryConfig)
  }

  @PostMapping(
    path = ["/{application}/pause"]
  )
  fun pause(@PathVariable("application") application: String) {
    resourcePauser.pauseApplication(application)
  }

  @PostMapping(
    path = ["/{application}/resume"]
  )
  fun resume(@PathVariable("application") application: String) {
    resourcePauser.resumeApplication(application)
  }
}
