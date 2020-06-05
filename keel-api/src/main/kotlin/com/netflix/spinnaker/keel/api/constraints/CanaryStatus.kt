package com.netflix.spinnaker.keel.api.constraints

data class CanaryStatus(
  val executionId: String,
  val region: String,
  val executionStatus: String,
  val scores: List<Double> = emptyList(),
  val scoreMessage: String?
)
