package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.lifecycle.LifecycleEvent
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventRepository
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventScope.PRE_DEPLOYMENT
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.FAILED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.NOT_STARTED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.RUNNING
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.SUCCEEDED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType.BAKE
import com.netflix.spinnaker.keel.lifecycle.StartMonitoringEvent
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import java.time.Clock
import java.time.Instant

abstract class LifecycleEventRepositoryTests<T: LifecycleEventRepository> : JUnit5Minutests {
  abstract fun factory(clock: Clock, publisher: ApplicationEventPublisher): T

  open fun T.flush() {}

  val clock = MutableClock()
  val publisher: ApplicationEventPublisher = mockk(relaxed = true)

  data class Fixture<T : LifecycleEventRepository>(
    val subject: T
  )
  val artifact = DockerArtifact(name = "my-artifact", deliveryConfigName = "my-config", branch = "main")
  val version1 = "123.4"
  val version2 = "123.5"
  val link = "http://www.bake.com/$version1"
  val v1Event1 = LifecycleEvent(
    scope = PRE_DEPLOYMENT,
    deliveryConfigName = artifact.deliveryConfigName!!,
    artifactReference = artifact.reference,
    artifactVersion = version1,
    type = BAKE,
    status = NOT_STARTED,
    id = "bake-$version1",
    text = "Submitting bake for version $version1",
    link = link,
    data = mapOf("hi" to "whatsup"),
    startMonitoring = true
  )
  val v1Event2 = v1Event1.copy(id = "bake-$version1-2")
  val v2event1 = v1Event1.copy(artifactVersion = version2, id = "bake-$version2")
  val v2event2 = v1Event1.copy(artifactVersion = version2, id = "bake-$version2-2")

  fun tests() = rootContext<Fixture<T>> {
    fixture {
      Fixture(subject = factory(clock, publisher))
    }

    after {
      subject.flush()
      clearAllMocks()
    }

    context("saving events") {
      before {
        subject.saveEvent(v1Event1)
      }

      test("can get saved event") {
        val events = subject.getEvents(artifact, version1)
        expect {
          that(events.size).isEqualTo(1)
          that(events.first().status).isEqualTo(NOT_STARTED)
          that(events.first().timestamp).isNotNull()
          that(events.first().data).isNotEmpty()
          that(events.first().data["hi"]).isEqualTo("whatsup")
        }
      }

      test("updates timestamp if duplicate event") {
        clock.tickMinutes(1)
        val now = clock.instant()
        subject.saveEvent(v1Event1.copy(timestamp = now))
        val events = subject.getEvents(artifact, version1)
        expect {
          that(events.size).isEqualTo(1)
          that(events.first().timestamp).isEqualTo(now)
        }
      }
    }

    context("turning events into steps") {
      before {
        subject.saveEvent(v1Event1)
      }

      test("can transform single event to step") {
        val steps = subject.getSteps(artifact, version1)
        expect {
          that(steps.size).isEqualTo(1)
          that(steps.first().status).isEqualTo(NOT_STARTED)
          that(steps.first().startedAt).isNotNull()
          that(steps.first().completedAt).isNull()
        }
      }

      context("multiple events single id") {
        before {
          clock.tickMinutes(1)
          subject.saveEvent(v1Event1.copy(status = RUNNING, text = null, link = null))
          clock.tickMinutes(1)
          subject.saveEvent(v1Event1.copy(status = SUCCEEDED, text = "Bake finished! Here's your cake", link = null))
        }

        test("can transform multiple events to step") {
          val steps = subject.getSteps(artifact, version1)
          expect {
            that(steps.size).isEqualTo(1)
            that(steps.first().status).isEqualTo(SUCCEEDED)
            that(steps.first().text).isEqualTo("Bake finished! Here's your cake")
            that(steps.first().link).isEqualTo(link)
            that(steps.first().startedAt).isNotNull()
            that(steps.first().completedAt).isNotNull()
            that(steps.first().startedAt).isNotEqualTo(steps.first().completedAt)
          }
        }
      }

      context("timestamps are a bit out of order") {
        before {
          clock.tickMinutes(1)
          subject.saveEvent(v1Event1.copy(status = SUCCEEDED, text = "Bake finished! Here's your cake", link = null))
          clock.tickMinutes(1)
          subject.saveEvent(v1Event1.copy(status = RUNNING, text = null, link = null))
        }

        test("step still shows completed event") {
          val steps = subject.getSteps(artifact, version1)
          expectThat(steps.size).isEqualTo(1)
          expectThat(steps.first()){
            get { status }.isEqualTo(SUCCEEDED)
            get { text }.isEqualTo("Bake finished! Here's your cake")
            get { link }.isEqualTo(link)
            get { startedAt }.isNotNull()
            get { completedAt }.isNotNull()
            get { startedAt }.isNotEqualTo(steps.first().completedAt)
          }
        }
      }

      context("multiple event ids") {
        before {
          clock.tickMinutes(1)
          subject.saveEvent(v1Event2)
          clock.tickMinutes(1)
          subject.saveEvent(v1Event1.copy(status = RUNNING, text = null, link = null))
          clock.tickMinutes(1)
          subject.saveEvent(v1Event2.copy(status = RUNNING, text = null, link = null))
          clock.tickMinutes(1)
          subject.saveEvent(v1Event1.copy(status = FAILED, text = "Oops, this failed", link = null))
          clock.tickMinutes(1)
          subject.saveEvent(v1Event2.copy(status = SUCCEEDED, text = "Bake succeeded", link = null))
        }

        test("can transform multiple events to multiple steps") {
          val steps = subject.getSteps(artifact, version1)
          expect {
            that(steps.size).isEqualTo(2)
            that(steps.first().status).isEqualTo(FAILED)
            that(steps.first().text).isEqualTo("Oops, this failed")
            that(steps.first().link).isEqualTo(link)
            that(steps.last().status).isEqualTo(SUCCEEDED)
            that(steps.last().text).isEqualTo("Bake succeeded")
            that(steps.last().link).isEqualTo(link)
          }
        }

        context("multiple versions") {
          before {
            subject.saveEvent(v2event1)
            clock.tickMinutes(1)
            subject.saveEvent(v2event2)
            clock.tickMinutes(1)
            subject.saveEvent(v2event1.copy(status = RUNNING, text = null, link = null))
            clock.tickMinutes(1)
            subject.saveEvent(v2event2.copy(status = RUNNING, text = null, link = null))
            clock.tickMinutes(1)
            subject.saveEvent(v2event1.copy(status = FAILED, text = "Oops, this failed", link = null))
            clock.tickMinutes(1)
            subject.saveEvent(v2event2.copy(status = SUCCEEDED, text = "Bake succeeded", link = null))
          }

          test("can pull multiple versions at once") {
            val steps = subject.getSteps(artifact)
            expectThat(steps.size).isEqualTo(4)
          }
        }

      }
    }

    context("hiding steps") {
      before {
        subject.saveEvent(v1Event1.copy(link = "i.am.a.link"))
      }

      test("no steps returned when link isn't valid") {
        val steps = subject.getSteps(artifact, version1)
        expectThat(steps).isEmpty()
      }
    }

    context("don't need a NOT_STARTED event to calculate steps") {
      before {
        subject.saveEvent(v1Event1.copy(status = RUNNING))
        clock.tickMinutes(1)
        subject.saveEvent(v1Event1.copy(status = SUCCEEDED, text = "Bake finished! Here's your cake", link = null))
      }

      test("successful step generated") {
        val steps = subject.getSteps(artifact, version1)
        expect {
          that(steps.size).isEqualTo(1)
          that(steps.first().status).isEqualTo(SUCCEEDED)
          that(steps.first().text).isEqualTo("Bake finished! Here's your cake")
          that(steps.first().link).isEqualTo(link)
          that(steps.first().startedAt).isNotNull()
          that(steps.first().completedAt).isNotNull()
        }
      }
    }

    context("backfilling monitoring") {
      context("single event does not have an ending status") {
        before {
          subject.saveEvent(v1Event1)
          clock.tickMinutes(1)
        }

        test("one minute old event does not trigger re-monitoring") {
          subject.getSteps(artifact, version1)
          verify { publisher wasNot Called }
        }

        test("ten minute old event does trigger re-monitoring") {
          clock.tickMinutes(10)
          subject.getSteps(artifact, version1)
          verify(exactly = 1) { publisher.publishEvent(ofType<StartMonitoringEvent>())}
        }
      }

      context("single event has ending status") {
        before {
          subject.saveEvent(v1Event1.copy(status = SUCCEEDED))
          clock.tickMinutes(10)
        }

        test("succeeded event does not trigger re-monitoring") {
          subject.getSteps(artifact, version1)
          verify { publisher wasNot Called }
        }
      }

      context("more than one event") {
        before {
          subject.saveEvent(v1Event1)
          clock.tickMinutes(1)
          subject.saveEvent(v1Event1.copy(status = SUCCEEDED))
          clock.tickMinutes(10)
        }

        test("several events do not trigger a re-monitoring") {
          subject.getSteps(artifact, version1)
          verify { publisher wasNot Called }
        }
      }
    }

    context("time") {
      var startTime: Instant? = null
      var endTime: Instant? = null
      before {
        startTime = clock.instant()
        subject.saveEvent(v1Event1)
        clock.tickMinutes(1)
        subject.saveEvent(v1Event1.copy(status = RUNNING, text = null, link = null))
        clock.tickMinutes(1)
        subject.saveEvent(v1Event1.copy(status = SUCCEEDED, text = "Bake succeeded", link = null))
        endTime = clock.instant()
      }
      test("time is correct") {
        val steps = subject.getSteps(artifact, version1)
        expect {
          that(steps.size).isEqualTo(1)
          that(steps.first().startedAt).isEqualTo(startTime)
          that(steps.first().completedAt).isEqualTo(endTime)
        }
      }
    }
  }
}
