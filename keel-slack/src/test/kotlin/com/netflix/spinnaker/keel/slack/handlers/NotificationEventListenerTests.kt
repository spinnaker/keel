package com.netflix.spinnaker.keel.slack.handlers

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.NotificationFrequency
import com.netflix.spinnaker.keel.api.NotificationType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.events.PinnedNotification
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.slack.NotificationEventListener
import com.netflix.spinnaker.keel.slack.SlackService
import com.netflix.spinnaker.keel.test.DummyArtifact
import com.netflix.spinnaker.time.MutableClock
import com.slack.api.model.kotlin_extension.block.SectionBlockBuilder
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.ZoneId

class NotificationEventListenerTests : JUnit5Minutests {

  class Fixture {
    val repository: KeelRepository = mockk()
    val releaseArtifact = DummyArtifact(reference = "release")
    val version0 = "fnord-1.0.0-h0.a0a0a0a"
    val version1 = "fnord-1.0.1-h1.b1b1b1b"
    val versions = listOf(version0, version1)

    val clock: MutableClock = MutableClock(
      Instant.parse("2020-03-25T00:00:00.00Z"),
      ZoneId.of("UTC")
    )

    val pin = EnvironmentArtifactPin("production", releaseArtifact.reference, version0, "keel@keel.io", "comment")
    val application1 = "fnord1"
    val singleArtifactEnvironments = listOf("test", "staging", "production").associateWith { name ->
      Environment(
        name = name,
        notifications = setOf(
          NotificationConfig(
            type = NotificationType.slack,
            address = "test",
            frequency = NotificationFrequency.verbose
          )
        )
      )
    }

    val singleArtifactDeliveryConfig = DeliveryConfig(
      name = "manifest_$application1",
      application = application1,
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(releaseArtifact),
      environments = singleArtifactEnvironments.values.toSet()
    )

    val slackService: SlackService = mockk()
    val gitDataGenerator: GitDataGenerator = mockk()
    val pinnedNotificationHandler : PinnedNotificationHandler = mockk(relaxUnitFun = true) {
      every {
        type
      } returns com.netflix.spinnaker.keel.notifications.NotificationType.ARTIFACT_PINNED
    }

    val pinnedNotification = PinnedNotification(singleArtifactDeliveryConfig, pin)
    val subject = NotificationEventListener(repository, clock, listOf(pinnedNotificationHandler))


    fun Collection<String>.toArtifactVersions(artifact: DeliveryArtifact) =
      map { PublishedArtifact(artifact.name, artifact.type, it) }
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture()
    }

    context("pin and unpin notifications") {
      before {
        every {
          slackService.getUsernameByEmail(any())
        } returns "@keel"

        every {
          slackService.sendSlackNotification("test", any())
        } just Runs

        every {
          gitDataGenerator.generateData(any(), any(), any())
        } returns SectionBlockBuilder()


        every { repository.getArtifactVersion(releaseArtifact, any(), any()) } returns versions.toArtifactVersions(releaseArtifact).first()

        every {
          repository.getArtifactVersionByPromotionStatus(any(), any(), any(), any())
        } returns versions.toArtifactVersions(releaseArtifact).first()

        every {
          repository.latestVersionApprovedIn(any(), any(), any())
        } returns versions.last()

      }

        test("slack notification was sent out") {
          subject.onPinnedNotification(pinnedNotification)
          verify  {
            pinnedNotificationHandler.sendMessage(any(), any())
          }
        }
      }
    }
}
