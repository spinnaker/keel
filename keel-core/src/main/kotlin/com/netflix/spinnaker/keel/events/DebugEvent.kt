package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.ResourceId
import java.time.Clock

/**
 * An event for tracking information about a resource that's used to make actuation decisions
 */
data class DebugEvent(
  val resourceId: ResourceId,
  val context: String,
  val message: String,
  val timestamp: Long
) {
  companion object {
    val clock: Clock = Clock.systemDefaultZone()
  }

  constructor(resourceId: ResourceId, context: String, message: String, clock: Clock = Companion.clock) : this(
    resourceId,
    context,
    message,
    clock.instant().toEpochMilli()
  )
}
