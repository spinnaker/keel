package com.netflix.spinnaker.keel.api.ec2

data class Rule(
  val priority: String,
  val conditions: List<Condition>?,
  val actions: List<Action>,
  val default: Boolean
)
