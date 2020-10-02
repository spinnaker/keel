package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.notifications.NotificationScope
import com.netflix.spinnaker.keel.notifications.Notifier

data class ResourceNotificationEvent(
  val scope: NotificationScope,
  val identifier: String,
  val notifier: Notifier,
  val message: NotifierMessage
)

data class NotifierMessage(
  val subject: String,
  val body: String
)
