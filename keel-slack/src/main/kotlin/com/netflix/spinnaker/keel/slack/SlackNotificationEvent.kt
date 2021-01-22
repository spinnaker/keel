package com.netflix.spinnaker.keel.slack

import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType
import java.time.Instant

abstract class SlackNotificationEvent(
  open val channel: String
)

data class SlackPinnedNotification(
  override val channel: String,
  val pin: EnvironmentArtifactPin,
  val currentArtifact: PublishedArtifact,
  val pinnedArtifact: PublishedArtifact,
  val time: Instant,
  val application: String
  ) : SlackNotificationEvent(channel)

data class SlackUnpinnedNotification(
  override val channel: String,
  val latestArtifact: PublishedArtifact?,
  val pinnedArtifact: PublishedArtifact?,
  val time: Instant,
  val application: String,
  val user: String,
  val targetEnvironment: String
  ) : SlackNotificationEvent(channel)

data class SlackMarkAsBadNotification(
  override val channel: String,
  val vetoedArtifact: PublishedArtifact,
  val time: Instant,
  val application: String,
  val user: String,
  val targetEnvironment: String,
  val comment: String?
) : SlackNotificationEvent(channel)

data class SlackPausedNotification(
  override val channel: String,
  val time: Instant,
  val application: String,
  val user: String?,
  val comment: String? = null
) : SlackNotificationEvent(channel)

data class SlackResumedNotification(
  override val channel: String,
  val time: Instant,
  val application: String,
  val user: String?,
  val comment: String? = null
) : SlackNotificationEvent(channel)

data class SlackLifecycleNotification(
  override val channel: String,
  val time: Instant,
  val artifact: PublishedArtifact,
  val type: LifecycleEventType,
  val application: String
) : SlackNotificationEvent(channel)
