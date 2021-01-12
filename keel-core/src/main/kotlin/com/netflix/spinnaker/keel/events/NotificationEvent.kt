package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.notifications.Notification
import com.netflix.spinnaker.keel.notifications.NotificationScope
import com.netflix.spinnaker.keel.notifications.NotificationType

//TODO[gyardeni]: deprecate it when new slack implementation will be in place
data class NotificationEvent(
  val scope: NotificationScope,
  val ref: String,
  val type: NotificationType,
  val message: Notification
)

