package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.notifications.Notification
import com.netflix.spinnaker.keel.notifications.NotificationScope
import com.netflix.spinnaker.keel.notifications.NotificationType

data class NotificationEvent(
  val scope: NotificationScope,
  val identifier: String,
  val notificationType: NotificationType,
  val message: Notification
)

