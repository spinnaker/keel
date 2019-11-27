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
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.ResourceStatus.UNHAPPY
import com.netflix.spinnaker.keel.persistence.UnhappyVetoRepository
import com.netflix.spinnaker.keel.veto.Veto
import com.netflix.spinnaker.keel.veto.VetoResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * A veto that stops keel from checking a resource for a configurable
 * amount of time so that we don't flap on a resource forever.
 */
@Component
class UnhappyVeto(
  private val resourceRepository: ResourceRepository,
  private val unhappyVetoRepository: UnhappyVetoRepository
) : Veto {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @Value("veto.unhappy.waiting-time")
  var waitingTime: String = "PT10M"

  override fun check(resource: Resource<*>) =
    check(resource.id, resource.spec.application)

  override fun check(resourceId: ResourceId, application: String): VetoResponse {
    val vetoStatus = unhappyVetoRepository.getVetoStatus(resourceId)
    if (vetoStatus.shouldSkip) {
      return deniedResponse("Resource is unhappy and will be checked again for a diff after $waitingTime")
    }

    // allow for a check every [waitingTime] even if the resource is unhappy
    if (vetoStatus.shouldRecheck) {
      unhappyVetoRepository.markUnhappy(resourceId, application)
      return allowedResponse()
    }

    return if (resourceRepository.getStatus(resourceId) == UNHAPPY) {
      unhappyVetoRepository.markUnhappy(resourceId, application)
      deniedResponse("Resource is unhappy and will be checked again for a diff after $waitingTime")
    } else {
      unhappyVetoRepository.markHappy(resourceId)
      allowedResponse()
    }
  }

  override fun messageFormat(): Map<String, Any> {
    TODO("not implemented")
  }

  override fun passMessage(message: Map<String, Any>) {
    TODO("not implemented")
  }

  override fun currentRejections(): List<String> =
    unhappyVetoRepository.getAll().map { it.toString() }.toList()

  override fun currentRejectionsByApp(application: String) =
    unhappyVetoRepository.getAllForApp(application).toList()
}
