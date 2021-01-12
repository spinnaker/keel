package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.notifications.NotificationType
import java.time.Instant

abstract class SlackNotificationEvent {
  open val notificationConfig: NotificationConfig? = null
  abstract val type: NotificationType
}


data class SlackPinnedNotification(
  override val notificationConfig: NotificationConfig,
  val pin: EnvironmentArtifactPin,
  val currentArtifact: PublishedArtifact?,
  val pinnedArtifact: PublishedArtifact?,
  val time: Instant,
  val application: String,
  override val type: NotificationType
) :SlackNotificationEvent() {
  constructor(notificationConfig: NotificationConfig,
              pin: EnvironmentArtifactPin,
              currentArtifact: PublishedArtifact?,
              pinnedArtifact: PublishedArtifact?,
              application: String,
              time: Instant) : this(
    notificationConfig,
    pin,
    currentArtifact,
    pinnedArtifact,
    time,
    application,
    NotificationType.PINNED
  )
}


  data class SlackUnpinnedNotification(
    override val notificationConfig: NotificationConfig,
    val latestArtifact: PublishedArtifact?,
    val pinnedArtifact: PublishedArtifact?,
    val time: Instant,
    val application: String,
    override val type: NotificationType
  ) : SlackNotificationEvent() {
    constructor(notificationConfig: NotificationConfig,
                latestArtifact: PublishedArtifact?,
                pinnedArtifact: PublishedArtifact?,
                application: String,
                time: Instant) : this(
      notificationConfig,
      latestArtifact,
      pinnedArtifact,
      time,
      application,
      NotificationType.UNPINNED
    )
  }

