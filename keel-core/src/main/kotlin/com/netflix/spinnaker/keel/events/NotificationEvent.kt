package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.core.api.PinnedEnvironment
import com.netflix.spinnaker.keel.notifications.Notification
import com.netflix.spinnaker.keel.notifications.NotificationScope
import com.netflix.spinnaker.keel.notifications.NotificationType

abstract class NotificationEvent{
  abstract val scope: NotificationScope
  open val ref: String? = null
  abstract val type: NotificationType
  open val message: Notification? = null
}

data class UnhealthyNotification(
  override val scope: NotificationScope,
  override val ref: String?,
  override val type: NotificationType,
  override val message: Notification?
  ) :NotificationEvent()

data class PinnedNotification(
  val config: DeliveryConfig,
  val pin: EnvironmentArtifactPin
): NotificationEvent() {
  override val type = NotificationType.PINNED
  override val scope = NotificationScope.ARTIFACT
}

data class UnpinnedNotification(
  val config: DeliveryConfig,
  val pinnedEnvironment: PinnedEnvironment?,
  val targetEnvironment: String,
  val user: String
): NotificationEvent() {
  override val type = NotificationType.UNPINNED
  override val scope = NotificationScope.ARTIFACT
}
