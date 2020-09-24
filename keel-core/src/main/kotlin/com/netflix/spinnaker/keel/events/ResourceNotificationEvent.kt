package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.notifications.Notifier

data class ResourceNotificationEvent(
  val resourceId: String,
  val notifier: Notifier,
  val message: NotifierMessage
)

data class NotifierMessage(
  val subject: String,
  val body: String
)