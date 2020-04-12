package com.netflix.spinnaker.keel.front50.model

data class Trigger(
  val type: String,
  val enabled: Boolean,
  val application: String? = null, // for pipeline trigger
  val pipeline: String? = null // for pipeline trigger
)
