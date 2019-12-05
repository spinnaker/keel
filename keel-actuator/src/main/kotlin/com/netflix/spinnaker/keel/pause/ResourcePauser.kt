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
package com.netflix.spinnaker.keel.pause

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.events.ResourceActuationPaused
import com.netflix.spinnaker.keel.events.ResourceActuationResumed
import com.netflix.spinnaker.keel.persistence.PausedRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.ResourceStatus.PAUSED
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class ResourcePauser(
  val resourceRepository: ResourceRepository,
  val pausedRepository: PausedRepository,
  val publisher: ApplicationEventPublisher
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  fun isPaused(resource: Resource<*>): Boolean =
    pausedRepository.applicationIsPaused(resource.application) || resourceRepository.getStatus(resource.id) == PAUSED

  fun pauseApplication(application: String) {
    log.info("Pausing application $application")
    pausedRepository.pauseApplication(application)
    resourcesInApplication(application).forEach { resource ->
      publisher.publishEvent(ResourceActuationPaused(resource, "Management of application $application has been paused"))
    }
  }

  fun resumeApplication(application: String) {
    log.info("Resuming application $application")
    pausedRepository.resumeApplication(application)
    resourcesInApplication(application).forEach { resource ->
      publisher.publishEvent(ResourceActuationResumed(resource))
    }
  }

  fun pauseResource(id: ResourceId) {
    val resource = resourceRepository.get(id)
    pauseResource(resource)
  }

  fun pauseResource(resource: Resource<*>) {
    publisher.publishEvent(ResourceActuationPaused(resource, "Management of this resource has been paused"))
  }

  fun resumeResource(id: ResourceId) {
    val resource = resourceRepository.get(id)
    resumeResource(resource)
  }

  fun resumeResource(resource: Resource<*>) {
    publisher.publishEvent(ResourceActuationResumed(resource))
  }

  fun pausedApplications(): List<String> =
    pausedRepository.pausedApplications()

  private fun resourcesInApplication(application: String): List<Resource<*>> =
    resourceRepository.getByApplication(application)
      .map { resourceRepository.get(ResourceId(it)) }
}
