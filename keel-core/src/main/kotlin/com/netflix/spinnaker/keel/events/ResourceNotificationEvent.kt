package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.notifications.NotificationMessage
import com.netflix.spinnaker.keel.notifications.NotificationScope
import com.netflix.spinnaker.keel.notifications.Notifier

data class ResourceNotificationEvent(
  val scope: NotificationScope,
  val identifier: String,
  val notifier: Notifier,
  val message: NotificationMessage
)

