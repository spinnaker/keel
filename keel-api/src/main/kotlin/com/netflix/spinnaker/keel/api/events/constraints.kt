/*
 * Copyright 2020 Netflix, Inc.
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
package com.netflix.spinnaker.keel.api.events

import com.netflix.spinnaker.keel.api.Constraint
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.constraints.ConstraintState

/**
 * TODO: Docs.
 */
sealed class ConstraintEvent(
  open val environment: Environment,
  open val constraint: Constraint
)

/**
 * Event published when the state of a constraint changes.
 */
data class ConstraintStateChanged(
  override val environment: Environment,
  override val constraint: Constraint,
  val previousState: ConstraintState?,
  val currentState: ConstraintState
) : ConstraintEvent(environment, constraint)
