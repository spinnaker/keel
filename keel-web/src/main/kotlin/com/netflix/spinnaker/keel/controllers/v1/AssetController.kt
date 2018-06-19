/*
 * Copyright 2017 Netflix, Inc.
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
package com.netflix.spinnaker.keel.controllers.v1

import com.netflix.spinnaker.config.KeelProperties
import com.netflix.spinnaker.keel.*
import com.netflix.spinnaker.keel.dryrun.DryRunAssetLauncher
import com.netflix.spinnaker.keel.model.PagingListCriteria
import com.netflix.spinnaker.keel.model.UpsertAssetRequest
import com.netflix.spinnaker.keel.scheduler.ScheduleService
import com.netflix.spinnaker.security.AuthenticatedRequest
import net.logstash.logback.argument.StructuredArguments
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.Instant
import javax.ws.rs.QueryParam

@RestController
@RequestMapping("/v1/intents")
class AssetController
@Autowired constructor(
  private val dryRunIntentLauncher: DryRunAssetLauncher,
  private val scheduleService: ScheduleService,
  private val assetRepository: AssetRepository,
  private val assetActivityRepository: AssetActivityRepository,
  private val keelProperties: KeelProperties
) {

  private val log = LoggerFactory.getLogger(javaClass)

  @RequestMapping(method = [RequestMethod.GET])
  fun getIntents(@QueryParam("status") statuses: Array<AssetStatus>? ): List<Asset<AssetSpec>> {
    statuses?.let {
      return assetRepository.getIntents(statuses.toList())
    }
    return assetRepository.getIntents()
  }

  @RequestMapping(value = ["/{id}"], method = [RequestMethod.GET])
  fun getAsset(@PathVariable("id") id: String) = assetRepository.getIntent(id)

  @RequestMapping(value = [""], method = [(RequestMethod.POST)])
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun upsertAsset(@RequestBody req: UpsertAssetRequest): Any {
    // TODO rz - validate intents
    // TODO rz - calculate graph

    // TODO rz - add "notes" API property for history/audit

    if (req.dryRun) {
      return req.assets.map { dryRunIntentLauncher.launch(it) }
    }

    val assetList = mutableListOf<UpsertAssetResponse>()

    req.assets.forEach { intent ->
      if (intent.createdAt == null) {
        intent.createdAt = Instant.now()
      }
      intent.updatedAt = Instant.now()
      intent.lastUpdatedBy = AuthenticatedRequest.getSpinnakerUser().orElse("[anonymous]")

      assetRepository.upsertIntent(intent)

      if (keelProperties.immediatelyRunIntents) {
        log.info("Immediately scheduling asset {}", StructuredArguments.value("asset", intent.id()))
        scheduleService.converge(intent)
      }

      assetList.add(UpsertAssetResponse(intent.id(), intent.status))
    }

    return assetList
  }

  @RequestMapping(value = ["/{id}"], method = [RequestMethod.DELETE])
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun deleteIntent(@PathVariable("id") id: String,
                   @RequestParam("soft", defaultValue = "true", required = false) soft: Boolean) {
    assetRepository.getIntent(id)
      .takeIf { it != null }
      ?.run {
        assetRepository.deleteIntent(id, soft)
      }
  }

  @RequestMapping(value = ["/{id}/log"], method = [RequestMethod.GET])
  fun getLog(@PathVariable("id") id: String,
             @RequestParam("limit", defaultValue = "10", required = false) limit: Int,
             @RequestParam("offset", defaultValue = "10", required = false) offset: Int,
             @RequestParam("kind", required = false) kind: String?): List<ActivityRecord> {
    if (kind == null) {
      return assetActivityRepository.getHistory(id, PagingListCriteria(limit, offset))
    }

    val clazz = activityRecordClassForName(kind) ?: throw IllegalArgumentException("Unknown activity record kind: $kind")

    return assetActivityRepository.getHistory(id, clazz, PagingListCriteria(limit, offset))
  }
}

data class UpsertAssetResponse(
  val intentId: String,
  val assetStatus: AssetStatus
)
