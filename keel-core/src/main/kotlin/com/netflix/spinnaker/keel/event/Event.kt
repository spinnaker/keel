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
package com.netflix.spinnaker.keel.event

import com.netflix.spinnaker.keel.Intent
import com.netflix.spinnaker.keel.IntentSpec

abstract class KeelEvent {
  val timestamp: Long = System.currentTimeMillis()
}

abstract class IntentAwareEvent : KeelEvent() {
  abstract val intent: Intent<IntentSpec>
}

data class AfterIntentUpsertEvent(
  override val intent: Intent<IntentSpec>
) : IntentAwareEvent()

data class AfterIntentDeleteEvent(
  override val intent: Intent<IntentSpec>
) : IntentAwareEvent()

data class BeforeIntentConvergeEvent(
  override val intent: Intent<IntentSpec>
) : IntentAwareEvent()

data class IntentConvergeTimeoutEvent(
  override val intent: Intent<IntentSpec>
) : IntentAwareEvent()

data class IntentNotFoundEvent(
  val intentId: String
) : KeelEvent()

data class IntentConvergeSuccessEvent(
  override val intent: Intent<IntentSpec>,
  val orchestrations: List<String>
) : IntentAwareEvent()

data class IntentConvergeFailureEvent(
  override val intent: Intent<IntentSpec>,
  val reason: String,
  val cause: Throwable?
) : IntentAwareEvent()
