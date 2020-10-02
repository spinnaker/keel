package com.netflix.spinnaker.keel.echo

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.NotificationFrequency.quiet
import com.netflix.spinnaker.keel.api.NotificationType.email
import com.netflix.spinnaker.keel.api.NotificationType.slack
import com.netflix.spinnaker.keel.events.NotifierMessage
import com.netflix.spinnaker.keel.events.ResourceHealthEvent
import com.netflix.spinnaker.keel.events.ResourceNotificationEvent
import com.netflix.spinnaker.keel.notifications.NotificationScope.RESOURCE
import com.netflix.spinnaker.keel.notifications.Notifier.UNHEALTHY
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.NoSuchResourceId
import com.netflix.spinnaker.keel.persistence.NotifierRepository
import com.netflix.spinnaker.keel.persistence.OrphanedResourceException
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import strikt.api.expectCatching
import strikt.assertions.isSuccess

class ResourceNotifierTests : JUnit5Minutests {

  class Fixture {
    val keelNotificationConfig: com.netflix.spinnaker.config.KeelNotificationConfig = mockk(relaxed = true)
    val echoService: EchoService = mockk(relaxed = true)
    val repository: KeelRepository = mockk(relaxed = true)
    val notifierRepository: NotifierRepository = mockk(relaxed = true)
    val subject = ResourceNotifier(echoService, repository, notifierRepository, keelNotificationConfig)

    val r = resource()
    val env = Environment(
      name = "test",
      resources = setOf(r),
      notifications = setOf(
        NotificationConfig(type = slack, address = "#ohmy", frequency = quiet),
        NotificationConfig(type = email, address = "oh@my.com", frequency = quiet)
      ),
      constraints = setOf()
    )
    val event = ResourceNotificationEvent(
      RESOURCE,
      r.id,
      UNHEALTHY,
      NotifierMessage("hi", "you")
    )
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture()
    }

    context("resource exists") {
      before {
        every { repository.getResource(r.id) } returns r
        every { repository.environmentFor(r.id) } returns env
      }

      context("new notification") {
        before {
          every { notifierRepository.addNotification(event.scope, event.identifier, event.notifier)} returns true
        }

        test("two notifications fire (slack and email)") {
          subject.onResourceNotificationEvent(event)
          coVerify(exactly = 2) { echoService.sendNotification(any()) }
        }
      }

      context("notification already exists") {
        before {
          every { notifierRepository.addNotification(event.scope, event.identifier, event.notifier)} returns false
        }

        test("no notifications fire") {
          subject.onResourceNotificationEvent(event)
          coVerify(exactly = 0) { echoService.sendNotification(any()) }
        }
      }
    }

    context("resource doesn't exist") {
      before {
        every { notifierRepository.addNotification(event.scope, event.identifier, event.notifier)} returns true
        every { repository.getResource(r.id) } throws NoSuchResourceId(r.id)
      }

      test("no notifications fire") {
        expectCatching {
          subject.onResourceNotificationEvent(event)
        }.isSuccess()
        coVerify(exactly = 0) { echoService.sendNotification(any()) }
      }
    }

    context("env doesn't exist") {
      before {
        every { notifierRepository.addNotification(event.scope, event.identifier, event.notifier)} returns true
        every { repository.getResource(r.id) } returns r
        every { repository.environmentFor(r.id) } throws OrphanedResourceException(r.id)
      }

      test("no notifications fire") {
        expectCatching {
          subject.onResourceNotificationEvent(event)
        }.isSuccess()
        coVerify(exactly = 0) { echoService.sendNotification(any()) }
      }
    }

    context("health events") {
      test("healthy triggers clear notification") {
        subject.onResourceHealthEvent(ResourceHealthEvent(r.id, r.application, true))
        verify(exactly = 1) {notifierRepository.clearNotification(RESOURCE, r.id, UNHEALTHY)}
      }

      test("unhealthy does nothing") {
        subject.onResourceHealthEvent(ResourceHealthEvent(r.id, r.application, false))
        verify(exactly = 0) {notifierRepository.clearNotification(RESOURCE, r.id, UNHEALTHY)}
      }
    }
  }
}
