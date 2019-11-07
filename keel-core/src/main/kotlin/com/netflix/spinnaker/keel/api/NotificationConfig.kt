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
package com.netflix.spinnaker.keel.api

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

// todo eb: this does not allow you to customize the notification message, but we can add that later.
data class NotificationConfig(
  val type: NotificationType,
  val address: String, // either slack channel or email address
  val frequency: NotificationFrequency
)

enum class NotificationFrequency(@JsonValue val friendlyName: String) {
  VERBOSE("verbose"), // notification on task starting, completing, failing
  NORMAL("normal"), // notification on task completing or failing
  QUIET("quiet"); // notification only for failure

  companion object {
    @JsonCreator
    @JvmStatic
    fun fromFriendlyName(friendlyName: String): NotificationFrequency? {
      return valueOf(friendlyName.toUpperCase())
    }
  }
}

enum class NotificationType(@JsonValue val friendlyName: String) {
  SLACK("slack"), EMAIL("email");

  companion object {
    @JsonCreator
    @JvmStatic
    fun fromFriendlyName(friendlyName: String): NotificationType? {
      return valueOf(friendlyName.toUpperCase())
    }
  }

  override fun toString(): String {
    return name.toLowerCase()
  }
}
