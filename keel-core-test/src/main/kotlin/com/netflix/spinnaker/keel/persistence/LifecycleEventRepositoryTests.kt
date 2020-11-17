package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.lifecycle.LifecycleEvent
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventRepository
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventScope.PRE_DEPLOYMENT
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.NOT_STARTED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.RUNNING
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.SUCCEEDED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType.BAKE
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expect
import strikt.assertions.isEqualTo
import java.time.Clock

abstract class LifecycleEventRepositoryTests<T: LifecycleEventRepository> : JUnit5Minutests {
  abstract fun factory(clock: Clock): T

  open fun T.flush() {}

  val clock = MutableClock()

  data class Fixture<T : LifecycleEventRepository>(
    val subject: T
  )
  val artifact = DockerArtifact(name = "my-artifact", deliveryConfigName = "my-config")
  val version = "123.4"
  val event = LifecycleEvent(
    scope = PRE_DEPLOYMENT,
    artifact = artifact,
    artifactVersion = version,
    type = BAKE,
    status = NOT_STARTED,
    id = "bake-$version",
    text = "Submitting bake for version $version",
    link = "www.bake.com/$version"
  )

  fun tests() = rootContext<Fixture<T>> {
    fixture {
      Fixture(subject = factory(clock))
    }

    after { subject.flush() }

    context("saving events") {
      before {
        subject.saveEvent(event)
      }

      test("can get saved event") {
        val events = subject.getEvents(artifact, version)
        expect {
          that(events.size).isEqualTo(1)
          that(events.first().status).isEqualTo(NOT_STARTED)
        }
      }
    }

    context("turning events into steps") {
      before {
        subject.saveEvent(event)
      }

      test("can transform single event to step") {
        val steps = subject.getSteps(artifact, version)
        expect {
          that(steps.size).isEqualTo(1)
          that(steps.first().status).isEqualTo(NOT_STARTED)
        }
      }

      context("multiple events"){
        before {
          clock.tickMinutes(1)
          subject.saveEvent(event.copy(status = RUNNING, text = null, link = null))
          clock.tickMinutes(1)
          subject.saveEvent(event.copy(status = SUCCEEDED, text = "Bake finished! Here's your cake", link = null))
        }
        test("can transform single event to step") {
          val steps = subject.getSteps(artifact, version)
          expect {
            that(steps.size).isEqualTo(1)
            that(steps.first().status).isEqualTo(SUCCEEDED)
            that(steps.first().text).isEqualTo("Bake finished! Here's your cake")
            that(steps.first().link).isEqualTo("www.bake.com/$version")
          }
        }
      }
    }

  }
}
