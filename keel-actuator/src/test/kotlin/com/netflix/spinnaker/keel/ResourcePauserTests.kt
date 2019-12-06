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
package com.netflix.spinnaker.keel

import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.events.ResourceActuationPaused
import com.netflix.spinnaker.keel.events.ResourceActuationResumed
import com.netflix.spinnaker.keel.pause.ResourcePauser
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.ResourceStatus.HAPPY
import com.netflix.spinnaker.keel.persistence.ResourceStatus.PAUSED
import com.netflix.spinnaker.keel.persistence.memory.InMemoryPausedRepository
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expect
import strikt.assertions.isFalse
import strikt.assertions.isTrue

class ResourcePauserTests : JUnit5Minutests {
  class Fixture {
    val resource1 = resource()
    val resource2 = resource()

    val resourceRepository: ResourceRepository = mockk() {
      every { get(resource1.id) } returns resource1
      every { get(resource2.id) } returns resource2
    }
    val pausedRepository = InMemoryPausedRepository()
    val publisher = mockk<ApplicationEventPublisher>(relaxUnitFun = true)
    val subject = ResourcePauser(resourceRepository, pausedRepository, publisher)
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    context("application wide") {
      before {
        every { resourceRepository.getStatus(resource1.id) } returns HAPPY
        every { resourceRepository.getStatus(resource2.id) } returns HAPPY
      }

      test("pause is reflected") {
        subject.pauseApplication(resource1.application)
        expect {
          that(subject.isPaused(resource1)).isTrue()
          that(subject.isPaused(resource2)).isTrue()
        }
        verify(exactly = 0) { publisher.publishEvent(ofType<ResourceActuationPaused>()) }
      }

      test("resume is reflected") {
        subject.resumeApplication(resource1.application)
        expect {
          that(subject.isPaused(resource1)).isFalse()
          that(subject.isPaused(resource2)).isFalse()
        }
        verify(exactly = 0) { publisher.publishEvent(ofType<ResourceActuationResumed>()) }
      }
    }

    context("just a resource") {
      before {
        every { resourceRepository.getStatus(resource1.id) } returns PAUSED
        every { resourceRepository.getStatus(resource2.id) } returns HAPPY
      }

      test("pause events fire") {
        subject.pauseResource(resource1.id)
        verify(exactly = 1) { publisher.publishEvent(ofType<ResourceActuationPaused>()) }
      }

      test("resume events fire") {
        subject.resumeResource(resource1.id)
        verify(exactly = 1) { publisher.publishEvent(ofType<ResourceActuationResumed>()) }
      }

      test("pause is reflected") {
        expect {
          that(subject.isPaused(resource1)).isTrue()
          that(subject.isPaused(resource2)).isFalse()
        }
      }
    }
  }
}
