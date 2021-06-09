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
package com.netflix.spinnaker.keel.veto

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.persistence.ResourceStatus

/**
 * Implement this interface to create a veto that will be consulted
 * before a resource is checked.
 *
 * todo emjburns: how do we get metrics on this? we should have something moniterable
 */
interface Veto {

  /**
   * The name of the veto.
   */
  fun name(): String = javaClass.simpleName

  /**
   * Check whether the resource can be checked according to this veto.
   */
  suspend fun check(resource: Resource<*>): VetoResponse

  /**
   * What's currently being vetoed.
   */
  fun currentRejections(): List<String> = TODO("not implemented")

  /**
   * What's currently being vetoed for an app.
   */
  fun currentRejectionsByApp(application: String): List<String> = TODO("not implemented")

  fun allowedResponse(): VetoResponse =
    VetoResponse(allowed = true, vetoName = name())

  fun deniedResponse(message: String, vetoArtifact: Boolean = true, suggestedStatus: ResourceStatus? = null): VetoResponse =
    VetoResponse(allowed = false, vetoName = name(), vetoArtifact = vetoArtifact, message = message, suggestedStatus = suggestedStatus)
}

data class VetoResponse(
  val allowed: Boolean,
  val vetoName: String,
  val vetoArtifact: Boolean = false,
  val message: String? = null,
  val suggestedStatus: ResourceStatus? = null
)
