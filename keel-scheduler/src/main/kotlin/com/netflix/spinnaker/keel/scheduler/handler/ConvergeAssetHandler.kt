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
package com.netflix.spinnaker.keel.scheduler.handler

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.keel.Asset
import com.netflix.spinnaker.keel.AssetRepository
import com.netflix.spinnaker.keel.AssetSpec
import com.netflix.spinnaker.keel.AssetStatus
import com.netflix.spinnaker.keel.event.*
import com.netflix.spinnaker.keel.orca.OrcaAssetLauncher
import com.netflix.spinnaker.keel.scheduler.ConvergeAsset
import com.netflix.spinnaker.q.MessageHandler
import com.netflix.spinnaker.q.Queue
import net.logstash.logback.argument.StructuredArguments.value
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock

private const val CANCELLATION_REASON_NOT_FOUND = "notFound"
private const val CANCELLATION_REASON_TIMEOUT = "timeout"

@Component
class ConvergeAssetHandler
@Autowired constructor(
  override val queue: Queue,
  private val assetRepository: AssetRepository,
  private val orcaIntentLauncher: OrcaAssetLauncher,
  private val clock: Clock,
  private val registry: Registry,
  private val applicationEventPublisher: ApplicationEventPublisher
) : MessageHandler<ConvergeAsset> {

  private val log = LoggerFactory.getLogger(javaClass)

  private val invocationsId = registry.createId("intent.invocations")
  private val canceledId = registry.createId("intent.cancellations")
  private val refreshesId = registry.createId("intent.refreshes")

  override fun handle(message: ConvergeAsset) {
    if (clock.millis() > message.timeoutTtl) {
      log.warn("Intent timed out, canceling converge for {}", value("intent", message.asset.id()))
      applicationEventPublisher.publishEvent(AssetConvergeTimeoutEvent(message.asset))

      registry.counter(canceledId.withTags("kind", message.asset.kind, "reason", CANCELLATION_REASON_TIMEOUT))
      return
    }

    val intent = getIntent(message)
    if (intent == null) {
      log.warn("Intent no longer exists, canceling converge for {}", value("intent", message.asset.id()))
      applicationEventPublisher.publishEvent(AssetConvergeNotFoundEvent(message.asset.id()))

      registry.counter(canceledId.withTags("kind", message.asset.kind, "reason", CANCELLATION_REASON_NOT_FOUND))
      return
    }

    applicationEventPublisher.publishEvent(BeforeAssetConvergeEvent(intent))

    try {
      orcaIntentLauncher.launch(intent)
        .takeIf { it.orchestrationIds.isNotEmpty() }
        ?.also { result ->
          applicationEventPublisher.publishEvent(AssetConvergeSuccessEvent(intent, result.orchestrationIds))

          if (intent.status.shouldIsolate()) {
            intent.status = AssetStatus.ISOLATED_INACTIVE
            assetRepository.upsertIntent(intent)
          }

          // TODO rz - MonitorOrchestrations is deprecated. Reconsider if we want to totally remove it.
//          queue.push(MonitorOrchestrations(intent.id(), intent.kind), Duration.ofMillis(10000))
        }
      registry.counter(invocationsId.withTags(message.asset.getMetricTags("result", "success")))
    } catch (t: Throwable) {
      log.error("Failed launching intent: ${intent.id()}", t)
      applicationEventPublisher.publishEvent(
        AssetConvergeFailureEvent(intent, t.message ?: "Could not determine reason", t)
      )
      registry.counter(invocationsId.withTags(message.asset.getMetricTags("result", "failed")))
    }
  }

  private fun getIntent(message: ConvergeAsset): Asset<AssetSpec>? {
    if (clock.millis() <= message.stalenessTtl) {
      return message.asset
    }

    log.debug("Refreshing intent state for {}", value("intent", message.asset.id()))
    registry.counter(refreshesId.withTags(message.asset.getMetricTags())).increment()

    return assetRepository.getIntent(message.asset.id())
  }

  override val messageType = ConvergeAsset::class.java
}
