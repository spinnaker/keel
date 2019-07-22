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

import com.netflix.spinnaker.keel.policy.exceptions.MalformedMessageException
import com.netflix.spinnaker.keel.policy.exceptions.PolicyNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/policies"])
class PolicyController(
  val policies: List<Policy>
) {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @GetMapping(
    produces = [MediaType.APPLICATION_JSON_VALUE]
  )
  fun getActivePolicies(): List<String> = policies.map { it.name() }

  @GetMapping(
    path = ["/{name}/message-format"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
  )
  fun getPolicyMessageFormat(@PathVariable name: String): Map<String, Any> {
    val policy = policies.find { it.name().equals(name, true) }
    if (policy == null) {
      throw PolicyNotFoundException(name)
    } else {
      return mapOf(
        "messageURL" to "POST /policies/{name}",
        "name" to name,
        "messageBodyFormat" to policy.messageFormat()
      )
    }
  }

  @GetMapping(
    path = ["/{name}/rejections"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
  )
  fun getPolicyRejections(@PathVariable name: String): List<String> {
    val policy = policies.find { it.name().equals(name, ignoreCase = true) }
    if (policy == null) {
      throw PolicyNotFoundException(name)
    } else {
      return policy.currentRejections()
    }
  }

  @PostMapping(
    path = ["/{name}"],
    produces = [MediaType.APPLICATION_JSON_VALUE]
  )
  fun passMessage(@PathVariable name: String, @RequestBody message: Map<String, Any>) =
    policies.forEach { policy ->
      if (policy.name().equals(name, ignoreCase = true)) {
        policy.passMessage(message)
      }
    }

  @ExceptionHandler(PolicyNotFoundException::class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  fun onNotFound(e: PolicyNotFoundException): Map<String, Any?> {
    log.error(e.message)
    return mapOf("message" to e.message)
  }

  @ExceptionHandler(MalformedMessageException::class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  fun onMalformedMessage(e: MalformedMessageException): Map<String, Any?> {
    log.error(e.message)
    return mapOf("message" to e.message)
  }
}
