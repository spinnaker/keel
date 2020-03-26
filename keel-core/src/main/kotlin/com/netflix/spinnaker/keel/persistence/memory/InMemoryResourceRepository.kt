/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.persistence.memory

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.core.api.ResourceSummary
import com.netflix.spinnaker.keel.events.ApplicationEvent
import com.netflix.spinnaker.keel.events.PersistentEvent
import com.netflix.spinnaker.keel.events.ResourceEvent
import com.netflix.spinnaker.keel.persistence.NoSuchResourceId
import com.netflix.spinnaker.keel.persistence.ResourceHeader
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.Instant.EPOCH

class InMemoryResourceRepository(
  private val clock: Clock = Clock.systemUTC()
) : ResourceRepository {

  private val resources = mutableMapOf<String, Resource<*>>()
  private val resourceEvents = mutableMapOf<String, MutableList<ResourceEvent>>()
  private val applicationEvents = mutableMapOf<String, MutableList<ApplicationEvent>>()
  private val lastCheckTimes = mutableMapOf<String, Instant>()
  private val resourceArtifacts = mutableMapOf<String, DeliveryArtifact>()

  fun dropAll() {
    resources.clear()
    resourceEvents.clear()
    applicationEvents.clear()
    lastCheckTimes.clear()
    resourceArtifacts.clear()
  }

  override fun allResources(callback: (ResourceHeader) -> Unit) {
    resources.values.forEach {
      callback(ResourceHeader(it))
    }
  }

  @Suppress("UNCHECKED_CAST")
  override fun get(id: String): Resource<out ResourceSpec> =
    resources.values.find { it.id == id } ?: throw NoSuchResourceId(id)

  override fun hasManagedResources(application: String): Boolean =
    resources.any { it.value.application == application }

  override fun getResourceIdsByApplication(application: String): List<String> =
    resources
      .filterValues { it.application == application }
      .map { it.value.id }

  override fun getResourcesByApplication(application: String): List<Resource<*>> =
    resources
      .filterValues { it.application == application }
      .map { it.value }
      .toList()

  override fun getResourceSummaries(deliveryConfig: DeliveryConfig): List<ResourceSummary> =
    resources
      .filterValues { it.application == deliveryConfig.application }
      .map { (_, resource) ->
        resource.toResourceSummary(deliveryConfig)
      }

  override fun store(resource: Resource<*>) {
    resources[resource.id] = resource
    lastCheckTimes[resource.id] = EPOCH
  }

  override fun delete(id: String) {
    resources
      .values
      .filter { it.id == id }
      .map { it.id }
      .singleOrNull()
      ?.also {
        resources.remove(it)
        resourceEvents.remove(it)
        resourceArtifacts.remove(it)
      }
      ?: throw NoSuchResourceId(id)
  }

  override fun applicationEventHistory(application: String, limit: Int): List<ApplicationEvent> {
    require(limit > 0) { "limit must be a positive integer" }
    return applicationEvents[application]
      ?.take(limit)
      ?: emptyList()
  }

  override fun applicationEventHistory(application: String, until: Instant): List<ApplicationEvent> {
    return applicationEvents[application]
      ?.takeWhile { !it.timestamp.isBefore(until) }
      ?: emptyList()
  }

  override fun eventHistory(id: String, limit: Int): List<ResourceEvent> {
    require(limit > 0) { "limit must be a positive integer" }
    return resourceEvents[id]
      ?.take(limit)
      ?: throw NoSuchResourceId(id)
  }

  override fun appendHistory(event: ResourceEvent) {
    appendHistory(resourceEvents, event)
  }

  override fun appendHistory(event: ApplicationEvent) {
    appendHistory(applicationEvents, event)
  }

  private fun <T : PersistentEvent> appendHistory(eventList: MutableMap<String, MutableList<T>>, event: T) {
    eventList.computeIfAbsent(event.uid) {
      mutableListOf()
    }
      .let {
        val mostRecentEvent = it.firstOrNull() // we get the first because the list is in descending order
        if (!event.ignoreRepeatedInHistory || event.javaClass != mostRecentEvent?.javaClass) {
          it.add(0, event)
        }
      }
  }

  override fun itemsDueForCheck(minTimeSinceLastCheck: Duration, limit: Int): Collection<Resource<out ResourceSpec>> {
    val cutoff = clock.instant().minus(minTimeSinceLastCheck)
    return lastCheckTimes
      .filter { it.value <= cutoff }
      .keys
      .take(limit)
      .also { uids ->
        uids.forEach {
          lastCheckTimes[it] = clock.instant()
        }
      }
      .map { resources[it]!! }
      .toList()
  }

  fun size() = resources.size
}
