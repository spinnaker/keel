/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.pause

import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.events.ApplicationActuationPaused
import com.netflix.spinnaker.keel.events.ApplicationActuationResumed
import com.netflix.spinnaker.keel.events.ResourceActuationPaused
import com.netflix.spinnaker.keel.events.ResourceActuationResumed
import com.netflix.spinnaker.keel.test.combinedInMemoryRepository
import com.netflix.spinnaker.keel.test.resource
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expect
import strikt.assertions.isFalse
import strikt.assertions.isTrue

class ActuationPauserTests : JUnit5Minutests {
  class Fixture {
    val resource1 = resource()
    val resource2 = resource()
    val clock = MutableClock()
    val repository = combinedInMemoryRepository(clock)
    val publisher = mockk<ApplicationEventPublisher>(relaxUnitFun = true)
    val subject = ActuationPauser(repository.resourceRepository, repository.pausedRepository, publisher, Clock.systemUTC())
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    before {
      repository.storeResource(resource1)
      repository.storeResource(resource2)
    }

    after {
      repository.dropAll()
    }

    context("application wide") {
      test("pause is reflected") {
        subject.pauseApplication(resource1.application, "keel@keel.io")
        expect {
          that(subject.isPaused(resource1)).isTrue()
          that(subject.isPaused(resource2)).isTrue()
        }
      }

      test("pause event is generated") {
        subject.pauseApplication(resource1.application, "keel@keel.io")
        verify(exactly = 1) {
          publisher.publishEvent(ofType<ApplicationActuationPaused>())
        }
        // no matching ResourceActuationPaused events are generated here as they are dynamically inserted into the
        // list by EventController to account for newly added resources
        verify(exactly = 0) {
          publisher.publishEvent(ofType<ResourceActuationPaused>())
        }
      }

      test("resume is reflected") {
        subject.resumeApplication(resource1.application)
        expect {
          that(subject.isPaused(resource1)).isFalse()
          that(subject.isPaused(resource2)).isFalse()
        }
      }

      test("resume event is generated") {
        subject.resumeApplication(resource1.application)
        verify(exactly = 1) {
          publisher.publishEvent(ofType<ApplicationActuationResumed>())
        }
        verify(exactly = 0) {
          publisher.publishEvent(ofType<ResourceActuationResumed>())
        }
      }
    }

    context("just a resource") {
      test("pause is reflected") {
        subject.pauseResource(resource1.id, "keel@keel.io")
        expect {
          that(subject.isPaused(resource1)).isTrue()
          that(subject.isPaused(resource2)).isFalse()
        }
      }

      test("resume is reflected") {
        subject.resumeResource(resource1.id)
        expect {
          that(subject.isPaused(resource1)).isFalse()
          that(subject.isPaused(resource2)).isFalse()
        }
        verify(exactly = 1) { publisher.publishEvent(ofType<ResourceActuationResumed>()) }
      }
    }
  }
}
