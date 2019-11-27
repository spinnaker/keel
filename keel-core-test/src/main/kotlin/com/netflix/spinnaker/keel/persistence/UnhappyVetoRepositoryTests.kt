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
package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.mockk
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import java.time.Clock
import java.time.Duration

abstract class UnhappyVetoRepositoryTests<T : UnhappyVetoRepository> : JUnit5Minutests {
  abstract fun factory(clock: Clock): T

  open fun T.flush() {}

  val clock = MutableClock()
  val resourceId = ResourceId("ec2:securityGroup:test:us-west-2:keeldemo-managed")
  val application = "keeldemo"

  data class Fixture<T : UnhappyVetoRepository>(
    val subject: T,
    val callback: (ResourceHeader) -> Unit = mockk(relaxed = true)
  )

  fun tests() = rootContext<Fixture<T>> {
    fixture {
      Fixture(subject = factory(clock))
    }

    after { subject.flush() }

    context("nothing currently vetoed") {
      test("no applications returned") {
        expectThat(subject.getAll()).hasSize(0)
      }
    }

    context("basic operations") {
      test("marking unhappy works") {
        subject.markUnhappy(resourceId, application)
        expectThat(subject.getAll()).hasSize(1)
      }

      test("marking happy works") {
        subject.markUnhappy(resourceId, application)
        subject.markHappy(resourceId)
        expectThat(subject.getAll()).hasSize(0)
      }
    }

    context("expiring the time") {
      before {
        subject.markUnhappy(resourceId, application)
      }

      test("should skip right after we mark unhappy") {
        expectThat(subject.shouldSkip(resourceId)).isEqualTo(true)
      }

      test("9 minutes later we should still skip") {
        clock.incrementBy(Duration.ofMinutes(9))
        expectThat(subject.shouldSkip(resourceId)).isEqualTo(true)
      }

      test("11 minutes later don't skip") {
        clock.incrementBy(Duration.ofMinutes(11))
        expectThat(subject.shouldSkip(resourceId)).isEqualTo(false)
      }
    }

    context("filtering works") {
      before {
        subject.markUnhappy(resourceId, application)
      }
      test("even if we don't mark happy again") {
        clock.incrementBy(Duration.ofMinutes(11))
        expectThat(subject.getAll()).hasSize(0)
      }
    }

    context("getting all by app name") {
      val bake1 = ResourceId("bakery:image:keeldemo")
      val bake2 = ResourceId("bakery:image:keel")
      val resource1 = ResourceId("ec2:securityGroup:test:us-west-2:keeldemo-managed")
      val resource2 = ResourceId("ec2:securityGroup:test:us-west-2:keel-managed")
      before {
        subject.markUnhappy(bake1, "keeldemo")
        subject.markUnhappy(bake2, "keel")
        subject.markUnhappy(resource1, "keeldemo")
        subject.markUnhappy(resource2, "keel")
      }

      test("get for keel returns only correct resources") {
        val resources = subject.getAllForApp("keel")
        expectThat(resources)
          .hasSize(2).containsExactlyInAnyOrder(bake2, resource2)
      }
    }
  }
}
