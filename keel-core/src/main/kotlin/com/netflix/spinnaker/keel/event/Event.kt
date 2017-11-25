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
  abstract val intent: Intent<IntentSpec>
}

data class OnIntentUpsertEvent(
  override val intent: Intent<IntentSpec>
) : KeelEvent()

data class OnIntentDeleteEvent(
  override val intent: Intent<IntentSpec>
) : KeelEvent()

data class OnStateChangeEvent(
  override val intent: Intent<IntentSpec>
) : KeelEvent()

// Triggered before launching the converge orchestrations: Can be used to
// do last-minute operations.
data class OnConvergeStartEvent(
  override val intent: Intent<IntentSpec>
) : KeelEvent()

data class OnConvergeLaunchEvent(
  override val intent: Intent<IntentSpec>,
  val orchestrations: List<String>
) : KeelEvent()

data class OnConvergeSuccessEvent(
  override val intent: Intent<IntentSpec>
) : KeelEvent()

data class OnConvergeFailureEvent(
  override val intent: Intent<IntentSpec>,
  val reason: String,
  val cause: Throwable?
) : KeelEvent()
