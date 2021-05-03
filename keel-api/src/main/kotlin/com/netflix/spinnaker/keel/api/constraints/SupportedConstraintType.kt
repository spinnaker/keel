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
package com.netflix.spinnaker.keel.api.constraints

import com.netflix.spinnaker.keel.api.Constraint

/**
 * Used to register subtypes of constraints for serialization
 */
data class SupportedConstraintType<T : Constraint>(
  val name: String,
  val type: Class<T>
)

/**
 * TODO: Docs.
 */
inline fun <reified T : Constraint> SupportedConstraintType(
  name: String
): SupportedConstraintType<T> =
  SupportedConstraintType(name, T::class.java)
