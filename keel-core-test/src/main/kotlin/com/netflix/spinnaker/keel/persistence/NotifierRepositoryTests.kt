package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.notifications.NotificationScope.RESOURCE
import com.netflix.spinnaker.keel.notifications.Notifier.UNHEALTHY
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.time.Clock

abstract class NotifierRepositoryTests<T: NotifierRepository> : JUnit5Minutests {
  abstract fun factory(clock: Clock): T

  open fun T.flush() {}

  val clock = MutableClock()
  val resourceId = "ec2:cluster:test:us-west-2:keeldemo-managed"
  val application = "keeldemo"

  data class Fixture<T : NotifierRepository>(
    val subject: T
  )

  fun tests() = rootContext<Fixture<T>> {
    fixture {
      Fixture(subject = factory(clock))
    }

    after { subject.flush() }

    context("new notification"){
      before {
        subject.addNotification(RESOURCE, "id", UNHEALTHY)
      }

      test("notification is due") {
        expectThat(subject.dueForNotification(RESOURCE, "id", UNHEALTHY)).isEqualTo(true)
      }

      test("marking sent means it's no longer due") {
        subject.markSent(RESOURCE, "id", UNHEALTHY)
        expectThat(subject.dueForNotification(RESOURCE, "id", UNHEALTHY)).isEqualTo(false)
      }

      test("removing means we should not notify") {
        subject.clearNotification(RESOURCE, "id", UNHEALTHY)
        expectThat(subject.dueForNotification(RESOURCE, "id", UNHEALTHY)).isEqualTo(false)
      }
    }
  }
}