package com.netflix.spinnaker.keel.slack.notifications

import com.netflix.spinnaker.keel.events.SlackUnpinnedNotification
import com.netflix.spinnaker.keel.slack.SlackNotifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class UnpinnedNotification (
  private val slackNotifier: SlackNotifier,
  @Value("\${spinnaker.baseUrl}") private val spinnakerBaseUrl: String
) {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @EventListener(SlackUnpinnedNotification::class)
  fun onUnpinnedEvent(event: SlackUnpinnedNotification) {
    //send notification
  }
}
