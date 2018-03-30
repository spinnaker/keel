/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.orca

import com.netflix.spinnaker.keel.ApplicationAwareIntentSpec
import com.netflix.spinnaker.keel.Intent
import com.netflix.spinnaker.keel.IntentSpec
import com.netflix.spinnaker.keel.IntentStatus.ACTIVE
import com.netflix.spinnaker.keel.event.AfterIntentDeleteEvent
import com.netflix.spinnaker.keel.event.AfterIntentUpsertEvent
import com.netflix.spinnaker.keel.model.Job
import com.netflix.spinnaker.keel.model.OrchestrationRequest
import com.netflix.spinnaker.keel.model.OrchestrationTrigger
import com.netflix.spinnaker.keel.tagging.*
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class EntityTaggingService(
  private val orcaService: OrcaService
) : TaggingService {

  private val log = LoggerFactory.getLogger(EntityTaggingService::class.java)

  @EventListener(AfterIntentUpsertEvent::class)
  fun onAfterIntentUpsert(event: AfterIntentUpsertEvent) {
    event.intent.also { intent ->
      if (intent.status != ACTIVE) {
        deleteIntentTags(intent)
        return
      }

      if (intent !is EntityTaggable) {
        log.warn("Did not try to tag any resources for intent, not implementing Taggable ({})", kv("intentId", intent.id()))
        return
      }

      // TODO rz - Not particularly sure what to do here. Shoveling everything into keel isn't the right thing to do.
      val application = intent.spec.let { (it as? ApplicationAwareIntentSpec)?.application ?: "keel" }

      val tags = intent.getEntityTags() ?: return

      log.info("Tagging managed resources for ${intent.id()}")
      tag(UpsertEntityTagsRequest(
        application = application,
        description = "This resource is declaratively managed, changing it through the UI may cause undesirable side effects",
        entityRef = tags.ref,
        tags = tags.tags
      ))
    }
  }

  @EventListener(AfterIntentDeleteEvent::class)
  fun onAfterIntentDelete(event: AfterIntentDeleteEvent) {
    deleteIntentTags(event.intent)
  }

  private fun deleteIntentTags(intent: Intent<IntentSpec>) {
    if (intent !is EntityTaggable) {
      return
    }

    // TODO rz - Not particularly sure what to do here. Shoveling everything into keel isn't the right thing to do.
    val application = intent.spec.let { (it as? ApplicationAwareIntentSpec)?.application ?: "keel" }

    removeTag(DeleteEntityTagsRequest(
      application = application,
      description = "Resource is no longer declaratively managed",
      id = intent.getEntityTagId()
    ))
  }

  override fun tag(tagRequest: TagRequest) {
    if (tagRequest is UpsertEntityTagsRequest) {
      orcaService.orchestrate(
        OrchestrationRequest(
          name = "keel",
          application = tagRequest.application,
          description = tagRequest.description,
          job = listOf(
            Job(tagRequest.type, mutableMapOf(
              "tags" to tagRequest.tags,
              "entityRef" to tagRequest.entityRef
            ))
          ),
          trigger = OrchestrationTrigger("upsertEntityTag:${tagRequest.entityRef}")
        )
      )
    }
  }

  override fun removeTag(tagRequest: TagRequest) {
    if (tagRequest is DeleteEntityTagsRequest) {
      orcaService.orchestrate(
        OrchestrationRequest(
          name = "keel",
          application = tagRequest.application,
          description = tagRequest.description,
          job = listOf(
            Job(tagRequest.type, mutableMapOf(
              "tags" to listOf(tagRequest.id)
            ))
          ),
          trigger = OrchestrationTrigger("deleteEntityTag:${tagRequest.id}")
        )
      )
    }
  }
}

data class UpsertEntityTagsRequest(
  val entityRef: EntityRef?,
  val tags: List<EntityTag>?,
  override val application: String,
  override val description: String
) : EntityTagRequest("upsertEntityTags", application, description)

data class DeleteEntityTagsRequest(
  val id: String,
  override val application: String,
  override val description: String
) : EntityTagRequest("deleteEntityTags", application, description)

open class EntityTagRequest(
  open val type: String,
  open val application: String,
  open val description: String
) : TagRequest
