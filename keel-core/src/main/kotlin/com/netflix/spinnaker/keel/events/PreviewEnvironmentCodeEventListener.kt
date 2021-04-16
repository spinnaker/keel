package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.PreviewEnvironmentSpec
import com.netflix.spinnaker.keel.api.scm.CommitCreatedEvent
import com.netflix.spinnaker.keel.api.scm.PrCreatedEvent
import com.netflix.spinnaker.keel.api.scm.PrMergedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Listens to code events that are relevant to managing preview environments.
 *
 * @see PreviewEnvironmentSpec
 */
@Component
class PreviewEnvironmentCodeEventListener {
  companion object {
    private val log by lazy { LoggerFactory.getLogger(PreviewEnvironmentCodeEventListener::class.java) }
  }

  @EventListener(PrCreatedEvent::class)
  fun handlePrCreated(event: PrCreatedEvent) {
    log.debug("Not yet implemented")
  }

  @EventListener(PrMergedEvent::class)
  fun handlePrMerged(event: PrMergedEvent) {
    log.debug("Not yet implemented")
  }

  @EventListener(CommitCreatedEvent::class)
  fun handleCommitCreated(event: CommitCreatedEvent) {
    log.debug("Not yet implemented")
  }
}