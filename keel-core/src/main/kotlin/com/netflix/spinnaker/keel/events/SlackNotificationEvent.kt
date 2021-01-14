package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import java.time.Instant

interface SlackNotificationEvent {
   val channel: String
}

data class SlackPinnedNotification(
  override val channel: String,
  val pin: EnvironmentArtifactPin,
  val currentArtifact: PublishedArtifact?,
  val pinnedArtifact: PublishedArtifact?,
  val time: Instant,
  val application: String
) :SlackNotificationEvent

data class SlackUnpinnedNotification(
  override val channel: String,
  val latestArtifact: PublishedArtifact?,
  val pinnedVersion: String?,
  val time: Instant,
  val application: String
  ) : SlackNotificationEvent
