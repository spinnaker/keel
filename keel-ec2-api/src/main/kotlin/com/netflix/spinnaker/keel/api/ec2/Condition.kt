package com.netflix.spinnaker.keel.api.ec2

data class Condition(
  val field: String,
  val values: List<String>
)
