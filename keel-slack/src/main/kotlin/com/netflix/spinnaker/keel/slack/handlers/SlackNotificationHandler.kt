package com.netflix.spinnaker.keel.slack.handlers

import com.netflix.spinnaker.keel.notifications.NotificationType
import com.netflix.spinnaker.keel.slack.SlackNotificationEvent

/**
 * Implement this interface to send different types of slack notifications. Each notification is being construct by [sendMessage]
 * See: [PinnedNotificationHandler] for example
 */

interface SlackNotificationHandler<T : SlackNotificationEvent> {
  val type: List<NotificationType>

  fun sendMessage(notification: T, channel: String)

}

fun <T : SlackNotificationEvent> Collection<SlackNotificationHandler<*>>.supporting(
  type: NotificationType
): SlackNotificationHandler<T>? =
  this.find { it.type.contains(type) } as? SlackNotificationHandler<T>
