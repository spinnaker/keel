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

import com.netflix.spinnaker.keel.persistence.UnhappyVetoRepository.UnhappyVetoStatus
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.experimental.minus
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import java.time.Clock
import java.time.Duration
import strikt.api.Assertion
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo

abstract class UnhappyVetoRepositoryTests<T : UnhappyVetoRepository> : JUnit5Minutests {
  abstract fun factory(clock: Clock): T

  open fun T.flush() {}

  val clock = MutableClock()
  val resourceId = "ec2:securityGroup:test:us-west-2:keeldemo-managed"
  val application = "keeldemo"
  val waitDuration = Duration.ofMinutes(10)

  data class Fixture<T : UnhappyVetoRepository>(
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
    }

    context("basic operations") {
      before {
        subject.markUnhappyForWaitingTime(resourceId, application)
      }

      test("marking unhappy works") {
        expectThat(subject.getAll()).hasSize(1)
      }

      test("marking happy works") {
        subject.delete(resourceId)
        expectThat(subject.getAll()).hasSize(0)
      }
    }

    context("expiring the time") {
      before {
        subject.markUnhappyForWaitingTime(resourceId, application)
      }

      test("should skip right after we mark unhappy") {
        val vetoStatus = subject.getOrCreateVetoStatus(resourceId, application, waitDuration)
        expect {
          that(vetoStatus).shouldSkip.isEqualTo(true)
          that(vetoStatus).shouldRecheck.isEqualTo(false)
        }
      }

      test("9 minutes later we should still skip") {
        clock.incrementBy(Duration.ofMinutes(9))
        val vetoStatus = subject.getOrCreateVetoStatus(resourceId, application, waitDuration)
        expect {
          that(vetoStatus).shouldSkip.isEqualTo(true)
          that(vetoStatus).shouldRecheck.isEqualTo(false)
        }
      }

      test("11 minutes later don't skip, instead recheck") {
        clock.incrementBy(Duration.ofMinutes(11))
        val vetoStatus = subject.getOrCreateVetoStatus(resourceId, application, waitDuration)
        expect {
          that(vetoStatus).shouldSkip.isEqualTo(false)
          that(vetoStatus).shouldRecheck.isEqualTo(true)
        }
      }
    }

    context("setting a null expiry time") {
      before {
        subject.markUnhappyForWaitingTime(resourceId, application, null)
      }

      test("should skip right after we mark unhappy with no timeout") {
        val vetoStatus = subject.getOrCreateVetoStatus(resourceId, application, null)
        expect {
          that(vetoStatus).shouldSkip.isEqualTo(true)
          that(vetoStatus).shouldRecheck.isEqualTo(false)
        }
      }

      test("should still skip after a long time when we mark unhappy with no timeout") {
        clock.incrementBy(Duration.ofDays(1000))
        val vetoStatus = subject.getOrCreateVetoStatus(resourceId, application, null)
        expect {
          that(vetoStatus).shouldSkip.isEqualTo(true)
          that(vetoStatus).shouldRecheck.isEqualTo(false)
        }
      }
    }

    context("filtering") {
      before {
        subject.markUnhappyForWaitingTime(resourceId, application)
      }
      test("filters out resources that are past the recheck time") {
        clock.incrementBy(Duration.ofMinutes(11))
        expectThat(subject.getAll()).hasSize(0)
      }
    }

    context("getting all by app name") {
      val bake1 = "bakery:image:keeldemo"
      val bake2 = "bakery:image:keel"
      val resource1 = "ec2:securityGroup:test:us-west-2:keeldemo-managed"
      val resource2 = "ec2:securityGroup:test:us-west-2:keel-managed"
      before {
        subject.markUnhappyForWaitingTime(bake1, "keeldemo")
        subject.markUnhappyForWaitingTime(bake2, "keel")
        subject.markUnhappyForWaitingTime(resource1, "keeldemo")
        subject.markUnhappyForWaitingTime(resource2, "keel")
      }

      test("get for keel returns only correct resources") {
        val resources = subject.getAllForApp("keel")
        expectThat(resources)
          .hasSize(2).containsExactlyInAnyOrder(bake2, resource2)
      }
    }
  }
}

private val Assertion.Builder<UnhappyVetoStatus>.shouldSkip: Assertion.Builder<Boolean>
  get() = get("should skip") { shouldSkip }

private val Assertion.Builder<UnhappyVetoStatus>.shouldRecheck: Assertion.Builder<Boolean>
  get() = get("should recheck") { shouldRecheck }
