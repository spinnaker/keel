package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.notifications.NotificationType
import java.time.Instant

interface SlackNotificationEvent {
   val notificationConfig: NotificationConfig?
   val type: NotificationType
}

data class SlackPinnedNotification(
  override val notificationConfig: NotificationConfig,
  val pin: EnvironmentArtifactPin,
  val currentArtifact: PublishedArtifact?,
  val pinnedArtifact: PublishedArtifact?,
  val time: Instant,
  val application: String
) :SlackNotificationEvent {
    override val type = NotificationType.PINNED
}

  data class SlackUnpinnedNotification(
    override val notificationConfig: NotificationConfig,
    val latestArtifact: PublishedArtifact?,
    val pinnedArtifact: PublishedArtifact?,
    val time: Instant,
    val application: String
  ) : SlackNotificationEvent {
      override val type = NotificationType.UNPINNED
  }
