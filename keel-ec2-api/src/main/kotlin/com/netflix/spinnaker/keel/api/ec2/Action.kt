package com.netflix.spinnaker.keel.api.ec2

data class Action(
  val type: String,
  val order: Int,
  val targetGroupName: String?,
  val redirectConfig: RedirectConfig?
)
