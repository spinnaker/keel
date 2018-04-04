/*
 * Copyright 2018 Netflix, Inc.
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
package com.netflix.spinnaker.keel.annotation

import com.netflix.spinnaker.keel.validation.IntentValidator
import javax.validation.Constraint


@Constraint(validatedBy = [IntentValidator::class])
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class IntentConstraint(
  val message: String = "Stored Intent requires an incremented CAS value"
)

//@Target(AnnotationTarget.TYPE)
//@Retention(AnnotationRetention.RUNTIME)
//annotation class List(vararg val value: IntentConstraint)
