package com.netflix.spinnaker.keel.slack.notifications

import com.netflix.spinnaker.keel.events.SlackUnpinnedNotification
import com.netflix.spinnaker.keel.notifications.NotificationType
import com.netflix.spinnaker.keel.slack.SlackNotificationHandler
import com.netflix.spinnaker.keel.slack.SlackNotifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class UnpinnedNotificationHandler (
  private val slackNotifier: SlackNotifier,
  @Value("\${spinnaker.baseUrl}") private val spinnakerBaseUrl: String
) : SlackNotificationHandler<SlackUnpinnedNotification> {

  override val type: NotificationType = NotificationType.UNPINNED
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun constructMessage(notification: SlackUnpinnedNotification) {
    TODO("Not yet implemented")
  }
}
