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
import com.netflix.spinnaker.keel.persistence.memory.InMemoryPausedRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryResourceRepository
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher

class ResourcePauserTests : JUnit5Minutests {
  val resource1 = resource()
  val resource2 = resource()

  class Fixture {
    val resourceRepository = InMemoryResourceRepository()
    val pausedRepository = InMemoryPausedRepository()
    val publisher = mockk<ApplicationEventPublisher>(relaxUnitFun = true)
    val subject = ResourcePauser(resourceRepository, pausedRepository, publisher)
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    before {
      resourceRepository.store(resource1)
      resourceRepository.store(resource2)
    }

    context("application wide") {
      test("pause affects 2 resources") {
        subject.pauseApplication(resource1.application)
        verify(exactly = 2) { publisher.publishEvent(ofType<ResourceActuationPaused>()) }
      }

      test("resume affects 2 resources") {
        subject.resumeApplication(resource1.application)
        verify(exactly = 2) { publisher.publishEvent(ofType<ResourceActuationResumed>()) }
      }
    }

    context("just a resource") {
      test("pause only the right resource") {
        subject.pauseResource(resource1.id)
        verify(exactly = 1) { publisher.publishEvent(ofType<ResourceActuationPaused>()) }
      }

      test("resume only the right resource") {
        subject.resumeResource(resource1.id)
        verify(exactly = 1) { publisher.publishEvent(ofType<ResourceActuationResumed>()) }
      }
    }
  }
}
