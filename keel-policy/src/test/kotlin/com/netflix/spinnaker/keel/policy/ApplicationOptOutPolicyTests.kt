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
package com.netflix.spinnaker.keel.policy

import com.netflix.spinnaker.keel.api.ResourceName
import com.netflix.spinnaker.keel.policy.application.ApplicationOptOutPolicy
import com.netflix.spinnaker.keel.policy.application.InMemoryApplicationOptOutRepository
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.hasSize
import strikt.assertions.isFalse
import strikt.assertions.isTrue

class ApplicationOptOutPolicyTests : JUnit5Minutests {
  val appName = "keeldemo"
  val resourceName = ResourceName("ec2:securityGroup:test:us-west-2:keeldemo-managed")

  internal

  class Fixture {
    val applicationOptOutRepository = InMemoryApplicationOptOutRepository()
    val subject = ApplicationOptOutPolicy(applicationOptOutRepository, configuredObjectMapper())
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    context("testing opt in flow") {
      after {
        applicationOptOutRepository.flush()
      }

      test("when no applications are opted out we allow any app") {
        val response = subject.check(resourceName)
        expectThat(response.allowed).isTrue()
      }

      test("when myapp is excluded, we don't allow it") {
        subject.passMessage(mapOf(
          "application" to appName,
          "optedOut" to true
        ))

        val response = subject.check(resourceName)
        expectThat(response.allowed).isFalse()
      }

      test("opting in/out works") {
        subject.passMessage(mapOf(
          "application" to appName,
          "optedOut" to true
        ))

        expectThat(subject.currentRejections())
          .hasSize(1)
          .contains(appName)

        subject.passMessage(mapOf(
          "application" to appName,
          "optedOut" to false
        ))

        expectThat(subject.currentRejections())
          .hasSize(0)
      }
    }
  }
}
