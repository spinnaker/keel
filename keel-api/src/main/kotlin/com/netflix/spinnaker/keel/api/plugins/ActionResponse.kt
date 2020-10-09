package com.netflix.spinnaker.keel.api.plugins

data class ActionResponse(
  val willTakeAction: Boolean = true,
  val message: String? = null
)
