package com.netflix.spinnaker.keel.events

/**
 * An event emitted when we calculate resource health
 */
data class ResourceHealthEvent(
  val resourceId: String,
  val application: String,
  val healthy: Boolean = true
)
