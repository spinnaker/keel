package com.netflix.spinnaker.keel.api.constraints

data class RegionalExecutionId(
  val region: String,
  val executionId: String
)
