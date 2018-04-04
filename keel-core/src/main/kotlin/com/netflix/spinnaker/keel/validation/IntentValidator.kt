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
package com.netflix.spinnaker.keel.validation

import com.netflix.spinnaker.keel.Intent
import com.netflix.spinnaker.keel.IntentRepository
import com.netflix.spinnaker.keel.IntentSpec
import com.netflix.spinnaker.keel.annotation.IntentConstraint
import org.springframework.stereotype.Component
import javax.validation.ConstraintValidator
import javax.validation.ConstraintValidatorContext

@Component
class IntentValidator(
  private val intentRepository: IntentRepository
) : ConstraintValidator<IntentConstraint, Intent<IntentSpec>> {

  override fun isValid(value: Intent<IntentSpec>, context: ConstraintValidatorContext): Boolean {
    // TODO rz - Should update the validator to not run when in dry-run mode?
    val storedIntent = intentRepository.getIntent(value.id())

    return ValidateIntentCommand(
      storedIntent = storedIntent,
      intent = value,
      context = context
    ).run {
      // TODO rz - Add the other validation things
      casVersion(this)
    }
  }

  private fun casVersion(command: ValidateIntentCommand): Boolean {
    return command.storedIntent
      ?.let { storedIntent ->
        if (storedIntent.cas == null) {
          return@let true
        }
        val requiredCasValue = storedIntent.cas + 1

        if (command.intent.cas == null) {
          command.context.buildConstraintViolationWithTemplate("No Intent CAS value set, but a value of " +
            "$requiredCasValue is required")
            .addPropertyNode("cas")
            .addConstraintViolation()
          return@let false
        }
        if (command.intent.cas != requiredCasValue) {
          command.context.buildConstraintViolationWithTemplate("Provided Intent CAS value '${command.intent.cas}' " +
            "does not match expected $requiredCasValue value")
            .addPropertyNode("cas")
            .addConstraintViolation()
          return@let false
        }

        return@let true
      }
      ?: true
  }

  override fun initialize(constraintAnnotation: IntentConstraint) {}
}

private data class ValidateIntentCommand(
  val storedIntent: Intent<IntentSpec>?,
  val intent: Intent<IntentSpec>,
  val context: ConstraintValidatorContext
)
