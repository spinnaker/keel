package com.netflix.spinnaker.keel.api.constraints

data class CanaryConstraintAttributes(
  val executions: Set<RegionalExecutionId> = emptySet(),
  val startAttempt: Int = 0,
  val status: Set<CanaryStatus> = emptySet()
) : ConstraintStateAttributes("canary")
