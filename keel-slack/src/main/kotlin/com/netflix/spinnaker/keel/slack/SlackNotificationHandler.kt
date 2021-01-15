package com.netflix.spinnaker.keel.slack

import com.netflix.spinnaker.keel.events.SlackNotificationEvent
import com.netflix.spinnaker.keel.notifications.NotificationType


interface SlackNotificationHandler <T: SlackNotificationEvent> {
  val type: NotificationType

  fun constructMessage(notification: T)
}
