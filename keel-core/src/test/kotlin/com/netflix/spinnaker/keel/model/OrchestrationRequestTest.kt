/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.model

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import org.junit.jupiter.api.Test

object OrchestrationRequestTest {

  @Test
  fun `should build job request`() {
    val req = OrchestrationRequest("wait for nothing", "keel", "my orchestration", listOf(
      Job("wait", mutableMapOf("waitTime" to 30))
    ), Trigger("1", "keel", "keel"))

    req.name shouldMatch equalTo("wait for nothing")
    req.application shouldMatch equalTo("keel")
    req.description shouldMatch equalTo("my orchestration")
    req.job[0]["type"] shouldMatch equalTo<Any?>("wait")
    req.job[0]["waitTime"] shouldMatch equalTo<Any?>(30)
  }
}
