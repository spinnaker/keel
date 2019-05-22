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
package com.netflix.spinnaker.keel.api.ec2.cluster

import com.fasterxml.jackson.annotation.JsonIgnore

data class Moniker(
  val application: String,
  val stack: String? = null,
  val detail: String? = null
) {
  @get:JsonIgnore
  val cluster: String
    get() = when {
      stack == null && detail == null -> application
      detail == null -> "$application-$stack"
      else -> "$application-${stack.orEmpty()}-$detail"
    }
}
