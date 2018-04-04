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
import com.netflix.spinnaker.keel.IntentStatus
import com.netflix.spinnaker.keel.test.GenericTestIntentSpec
import com.netflix.spinnaker.keel.test.TestIntent
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.Test
import javax.validation.ConstraintValidatorContext

object IntentValidatorTest {

  @Test
  fun `cas version should not be validated if not present in stored intent`() {
    val intentRepository = mock<IntentRepository>()
    whenever(intentRepository.getIntent(any())).thenReturn(TestIntent(
      GenericTestIntentSpec("hello"),
      mutableMapOf(),
      mutableListOf(),
      listOf()
    ))

    val constraintContext = mock<ConstraintValidatorContext>()
    val subject = IntentValidator(intentRepository)

    val intent = TestIntent(
      GenericTestIntentSpec("hello"),
      mutableMapOf(),
      mutableListOf(),
      listOf()
    )

    val result = subject.isValid(intent, constraintContext)

    assert(result)
  }

  @Test
  fun `cas version should be validated if provided in stored intent`() {
    val intentRepository = mock<IntentRepository>()
    whenever(intentRepository.getIntent(any())).thenReturn(CasIntent(4L))

    val constraintContext = mock<ConstraintValidatorContext>()
    val constraintBuilder = mock<ConstraintValidatorContext.ConstraintViolationBuilder>()
    val nodeBuilder = mock<ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext>()
    whenever(constraintContext.buildConstraintViolationWithTemplate(any())).thenReturn(constraintBuilder)
    whenever(constraintBuilder.addPropertyNode(any())).thenReturn(nodeBuilder)
    whenever(nodeBuilder.addConstraintViolation()).thenReturn(constraintContext)

    val subject = IntentValidator(intentRepository)

    subject.isValid(CasIntent(null), constraintContext).also { result ->
      assert(!result, { "null cas version should be invalid" })
    }
    subject.isValid(CasIntent(4L), constraintContext).also { result ->
      assert(!result, { "equal cas version should not be valid" })
    }
    subject.isValid(CasIntent(6L), constraintContext).also { result ->
      assert(!result, { "jumping cas version should not be valid" })
    }
    subject.isValid(CasIntent(5L), constraintContext).also { result ->
      assert(result, { "incremented cas version should be valid" })
    }
  }
}

private class CasIntent(
  cas: Long?
) : Intent<GenericTestIntentSpec>(
  schema = "0",
  kind = "CasIntent",
  spec = GenericTestIntentSpec("hello"),
  status = IntentStatus.ACTIVE,
  labels = mutableMapOf(),
  attributes = mutableListOf(),
  policies = listOf(),
  cas = cas
) {
  override val id = "cas"
}
