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
package com.netflix.spinnaker.keel.policy.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.ResourceName
import com.netflix.spinnaker.keel.policy.Policy
import com.netflix.spinnaker.keel.policy.PolicyResponse
import com.netflix.spinnaker.keel.policy.exceptions.MalformedMessageException
import org.springframework.stereotype.Component

@Component
class ApplicationOptOutPolicy(
  val applicationOptOutRepository: ApplicationOptOutRepository,
  val objectMapper: ObjectMapper
) : Policy {

  override fun check(name: ResourceName): PolicyResponse {
    val appName = name.toString().split(":").last().split("-").first()
    if (applicationOptOutRepository.appEnabled(appName)) {
      return PolicyResponse(allowed = true)
    }
    return PolicyResponse(allowed = false, message = "Application $name has been opted out.")
  }

  override fun messageFormat() =
    mapOf(
      "application" to "String",
      "optedOut" to "Boolean"
    )

  override fun passMessage(message: Map<String, Any>) {
    try {
      val appInfo = objectMapper.convertValue(message, MessageFormat::class.java)
      if (appInfo.optedOut) {
        applicationOptOutRepository.optOut(appInfo.application)
      } else {
        applicationOptOutRepository.optIn(appInfo.application)
      }
    } catch (e: IllegalArgumentException) {
      throw MalformedMessageException(this.javaClass.simpleName, messageFormat())
    }
  }

  override fun currentRejections(): List<String> {
    return applicationOptOutRepository.getAll().toList()
  }
}

data class MessageFormat(
  val application: String,
  val optedOut: Boolean
)
