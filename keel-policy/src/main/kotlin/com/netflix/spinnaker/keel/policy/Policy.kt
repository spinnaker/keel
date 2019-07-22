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

/**
 * Implement this interface to create a policy that will be consulted
 * before a resource is checked.
 *
 * todo emjburns: how do we get metrics on this? we should have something moniterable
 */
interface Policy {

  /**
   * The name of the policy
   */
  fun name(): String = javaClass.simpleName

  /**
   * Check whether the resource (identified by name) can be checked according to this policy
   */
  fun check(name: ResourceName): PolicyResponse

  /**
   * The message format a policy accepts
   */
  fun messageFormat(): Map<String, Any>

  /**
   * Pass a message to a policy
   */
  fun passMessage(message: Map<String, Any>)

  /**
   * What's currently being rejected
   */
  fun currentRejections(): List<String>
}

data class PolicyResponse(
  val allowed: Boolean,
  val message: String? = null
)
