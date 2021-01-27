package com.netflix.spinnaker.keel.slack.handlers

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.NotificationFrequency
import com.netflix.spinnaker.keel.api.NotificationType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.events.ApplicationActuationPaused
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
import strikt.api.expectThrows
import java.time.Instant
import java.time.ZoneId
import com.netflix.spinnaker.keel.notifications.NotificationType as Type

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

    val pin = EnvironmentArtifactPin("test", releaseArtifact.reference, version0, "keel@keel.io", "comment")
    val application1 = "fnord1"
    val singleArtifactEnvironments = listOf(
      Environment(
        name = "test",
        notifications = setOf(
          NotificationConfig(
            type = NotificationType.slack,
            address = "test",
            frequency = NotificationFrequency.verbose
          )
        )
      ),
      Environment(
        name = "staging",
        notifications = setOf(
          NotificationConfig(
            type = NotificationType.slack,
            address = "staging",
            frequency = NotificationFrequency.verbose
          )
        )
      ),
      Environment(
        name = "production",
        notifications = setOf(
          NotificationConfig(
            type = NotificationType.slack,
            address = "prod",
            frequency = NotificationFrequency.verbose
          ),
          NotificationConfig(
            type = NotificationType.slack,
            address = "prod#2",
            frequency = NotificationFrequency.quiet
          ),
          NotificationConfig(
            type = NotificationType.email,
            address = "@prod",
            frequency = NotificationFrequency.verbose
          )
        )
      ),
    )

    val singleArtifactDeliveryConfig = DeliveryConfig(
      name = "manifest_$application1",
      application = application1,
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(releaseArtifact),
      environments = singleArtifactEnvironments.toSet()
    )

    val slackService: SlackService = mockk()
    val gitDataGenerator: GitDataGenerator = mockk()
    val pinnedNotificationHandler: PinnedNotificationHandler = mockk(relaxUnitFun = true) {
      every {
        type
      } returns Type.ARTIFACT_PINNED
    }

    val unpinnedNotificationHandler: UnpinnedNotificationHandler = mockk(relaxUnitFun = true) {
      every {
        type
      } returns Type.ARTIFACT_UNPINNED
    }

    val pausedNotificationHandler: PausedNotificationHandler = mockk(relaxUnitFun = true) {
      every {
        type
      } returns Type.APPLICATION_PAUSED
    }


    val pinnedNotification = PinnedNotification(singleArtifactDeliveryConfig, pin)
    val pausedNotification = ApplicationActuationPaused(application1, clock.instant(), "user1")
    val subject = NotificationEventListener(repository, clock, listOf(pinnedNotificationHandler, pausedNotificationHandler, unpinnedNotificationHandler))


    fun Collection<String>.toArtifactVersions(artifact: DeliveryArtifact) =
      map { PublishedArtifact(artifact.name, artifact.type, it) }
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture()
    }

    context("sending notifications with a single environment handler") {
      before {
        every {
          slackService.getUsernameByEmail(any())
        } returns "@keel"

        every {
          slackService.sendSlackNotification("test", any(), any(), any(), any())
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

      test("the right (pinned) slack notification was sent out just once") {
        subject.onPinnedNotification(pinnedNotification)
        verify(exactly = 1) {
          pinnedNotificationHandler.sendMessage(any(), any())
        }
        verify(exactly = 0) {
          unpinnedNotificationHandler.sendMessage(any(), any())
        }
      }

      test("only slack notifications are sending out") {
        subject.onPinnedNotification(pinnedNotification.copy(pin = pin.copy(targetEnvironment = "production")))
        verify(exactly = 2) {
          pinnedNotificationHandler.sendMessage(any(), any())
        }
      }

      test("catch an NoSuchElementException exception if an environment was not found") {
        expectThrows<NoSuchElementException> {
          subject.onPinnedNotification(pinnedNotification.copy(pin = pin.copy(targetEnvironment = "test#2")))
        }
      }
    }

    context("sending notifications to multiple environments") {
      before {
        every {
          repository.getDeliveryConfigForApplication(application1)
        } returns singleArtifactDeliveryConfig
      }
      test("sending pause notifications") {
        subject.onApplicationActuationPaused(pausedNotification)
        verify(exactly = 4) {
          pausedNotificationHandler.sendMessage(any(), any())
        }
      }
    }
  }
}
