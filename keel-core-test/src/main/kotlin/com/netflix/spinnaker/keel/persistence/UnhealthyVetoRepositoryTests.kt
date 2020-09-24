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

import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expect
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.hasSize
import strikt.assertions.isFalse
import strikt.assertions.isSuccess
import strikt.assertions.isTrue
import java.time.Clock

abstract class UnhealthyVetoRepositoryTests<T : UnhealthyVetoRepository> : JUnit5Minutests {
  abstract fun factory(clock: Clock): T

  open fun T.flush() {}

  val clock = MutableClock()
  val resourceId = "ec2:cluster:test:us-west-2:keeldemo-managed"
  val application = "keeldemo"

  data class Fixture<T : UnhealthyVetoRepository>(
    val subject: T
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

      test("can mark unhealthy") {
        expectCatching {
          subject.markUnhealthy(resourceId, application)
        }
          .isSuccess()
      }

      test("can mark healthy as a no op") {
        expectCatching {
          subject.markHealthy(resourceId)
        }
          .isSuccess()
      }
    }

    context("basic operations") {
      before {
        subject.markUnhealthy(resourceId, application)
      }

      test("marking unhealthy works") {
        expect{
          that(subject.isHealthy(resourceId)).isFalse()
          that(subject.getAll()).hasSize(1)
        }
      }

      test("deleting works") {
        subject.delete(resourceId)
        expectThat(subject.getAll()).hasSize(0)
      }
    }

    context("unhealthy to healthy") {
      before {
        subject.markUnhealthy(resourceId, application)
        subject.markHealthy(resourceId)
      }

      test("record is healthy") {
          expectThat(subject.isHealthy(resourceId)).isTrue()
      }
    }


    context("filtering") {
      before {
        subject.markUnhealthy(resourceId, application)
        subject.markHealthy(resourceId)
      }
      test("filters out healthy resources") {
        expectThat(subject.getAll()).hasSize(0)
      }
    }

    context("getting all by app name") {
      val resource1 = "ec2:cluster:test:us-west-2:keeldemo-managed"
      val resource2 = "ec2:cluster:test:us-west-2:keel-managed"
      before {
        subject.markUnhealthy(resource1, "keeldemo")
        subject.markUnhealthy(resource2, "keel")
      }

      test("get for keel returns only correct resources") {
        val resources = subject.getAllForApp("keel")
        expectThat(resources)
          .hasSize(1).containsExactlyInAnyOrder(resource2)
      }
    }
  }
}
