package com.netflix.spinnaker.keel.core.api

import com.netflix.spinnaker.keel.api.Validation

data class TestContainerValidation(
  val container: String
) : Validation {
  override val type = "test-container"
}
