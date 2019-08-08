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

package com.netflix.spinnaker.keel.tagging

import com.netflix.spinnaker.keel.actuation.ResourcePersister
import com.netflix.spinnaker.keel.api.ResourceName
import com.netflix.spinnaker.keel.api.SPINNAKER_API_V1
import com.netflix.spinnaker.keel.api.SubmittedMetadata
import com.netflix.spinnaker.keel.api.SubmittedResource
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.Credential
import com.netflix.spinnaker.keel.events.CreateEvent
import com.netflix.spinnaker.keel.events.DeleteEvent
import com.netflix.spinnaker.keel.persistence.NoSuchResourceException
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.tags.EntityRef
import com.netflix.spinnaker.keel.tags.EntityTag
import com.netflix.spinnaker.keel.tags.KEEL_TAG_NAME
import com.netflix.spinnaker.keel.tags.TagValue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * We want each resource keel manages to have an entity tag on it linking the resource to the keel id.
 *
 * It might be very annoying to have all of these "extra" desired states - one for each resource.
 * A future optimization could be to look at this and do a bulk check for all resources.
 *
 * Another future thing could be to make sure that for each tag we have, we also have a resource
 * that we're managing.
 */
class ResourceTagger(
  private val resourceRepository: ResourceRepository,
  private val resourcePersister: ResourcePersister,
  private val cloudDriverService: CloudDriverService,
  private var removedTagRetentionHours: Long = 24,
  private var clock: Clock
) {
  private val log = LoggerFactory.getLogger(javaClass)

  private var accounts: Set<Credential> = emptySet()
  private var accountsUpdateTimeS = 0L
  private var accountsUpdateFrequencyS = TimeUnit.MINUTES.toSeconds(10)
  private val transforms = mapOf(
    "ec2" to "aws"
  )

  private val entityTypeTransforms = mapOf(
    "classic-load-balancer" to "loadbalancer",
    "application-load-balancer" to "loadbalancer"
  )

  private val taggableResources = listOf(
    "cluster",
    "securityGroup",
    "classic-load-balancer",
    "application-load-balancer"
  )

  @EventListener(CreateEvent::class)
  fun onCreateEvent(event: CreateEvent) {
    if (event.resourceName.shouldTag()) {
      log.debug("Persisting tag desired for resource {} because it exists now", event.resourceName.toString())
      val spec = event.resourceName.generateKeelTagSpec()
      persistTagState(spec)
    }
  }

  @EventListener(DeleteEvent::class)
  fun onDeleteEvent(event: DeleteEvent) {
    if (event.resourceName.shouldTag()) {
      log.debug("Persisting no tag desired for resource {} because it is no longer managed", event.resourceName.toString())
      val entityRef = event.resourceName.toEntityRef()
      val spec = KeelTagSpec(
        keelId = event.resourceName.toString(),
        entityRef = entityRef,
        tagState = TagNotDesired(startTime = clock.millis())
      )
      persistTagState(spec)
    }
  }

  private fun persistTagState(spec: KeelTagSpec) {
    val submitted = spec.toSubmittedResource()
    val name = ResourceName(spec.generateTagNameFromKeelId())

    if (tagExists(name)) {
      resourcePersister.update(name, submitted)
    } else {
      resourcePersister.create(submitted)
    }
  }

  private fun tagExists(tagResourceName: ResourceName): Boolean {
    try {
      resourceRepository.get(tagResourceName, KeelTagSpec::class.java)
      return true
    } catch (e: NoSuchResourceException) {
      return false
    }
  }

  @Scheduled(fixedDelayString = "\${keel.resource-tagger.tag-cleanup-frequency:P1D}")
  fun removeTags() {
    log.info("Cleaning up old keel tags")
    resourceRepository.allResources { resourceHeader ->
      if (resourceHeader.apiVersion == SPINNAKER_API_V1.subApi("tag")) {
        val tagResource = resourceRepository.get(resourceHeader.name, KeelTagSpec::class.java)
        if (tagResource.spec.tagState is TagNotDesired) {
          val tagState = tagResource.spec.tagState as TagNotDesired
          if (tagState.startTime < clock.millis() - Duration.ofHours(removedTagRetentionHours).toMillis()) {
            resourcePersister.delete(resourceHeader.name)
          }
        }
      }
    }
  }

  @Scheduled(fixedDelayString = "\${keel.resource-tagger.account-sync-frequency:PT600S}")
  private fun syncAccounts() {
    val now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
    if (now - accountsUpdateFrequencyS > accountsUpdateTimeS) {
      log.info("Refreshing clouddriver accounts")
      runBlocking { accounts = cloudDriverService.listCredentials() }
      accountsUpdateTimeS = now
    }
  }

  private fun KeelTagSpec.generateTagNameFromKeelId() = "tag:keel-tag:$keelId"

  private fun KeelTagSpec.toSubmittedResource() =
    SubmittedResource(
      metadata = SubmittedMetadata("keel@spinnaker.io"),
      apiVersion = SPINNAKER_API_V1.subApi("tag"),
      kind = "keel-tag",
      spec = this
    ) as SubmittedResource<Any>

  private fun ResourceName.generateKeelTagSpec() =
    KeelTagSpec(
      toString(),
      toEntityRef(),
      generateTagDesired(),
      toEntityRef().application
    )

  private fun ResourceName.generateTagDesired() =
    TagDesired(tag = EntityTag(
      value = TagValue(
        message = KEEL_TAG_MESSAGE,
        keelResourceId = toString(),
        type = "notice"
      ),
      namespace = KEEL_TAG_NAMESPACE,
      valueType = "object",
      name = KEEL_TAG_NAME
    )
    )

  private fun ResourceName.shouldTag(): Boolean {
    val (_, resourceType, _, _, _) = toString().split(":")
    return taggableResources.contains(resourceType)
  }

  private fun ResourceName.toEntityRef(): EntityRef {
    val (pluginGroup, resourceType, account, region, resourceId) = toString().toLowerCase().split(":")
    val accountId = try {
      val fullAccount = accounts.first { a -> a.name == account }
      fullAccount.attributes.getOrDefault("accountId", account).toString()
    } catch (e: NoSuchElementException) {
      log.error("Can't find $account in list of Clouddriver accounts. Valid options: {}", account, accounts)
      account
    }

    return EntityRef(
      entityType = entityTypeTransforms.getOrDefault(resourceType, resourceType),
      entityId = resourceId,
      application = resourceId.substringBefore("-"),
      region = region,
      account = account,
      accountId = accountId,
      cloudProvider = transforms.getOrDefault(pluginGroup, pluginGroup)
    )
  }
}
