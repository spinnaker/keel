package com.netflix.spinnaker.keel.lifecycle

import java.time.Instant

data class LifecycleEvent(
  val scope: LifecycleEventScope,
  val artifactRef: String,
  val artifactVersion: String,
  val type: LifecycleEventType,
  val id: String? = null,
  val status: LifecycleEventStatus,
  val text: String? = null,
  val link: String? = null,
  val timestamp: Instant? = null
) {
  fun toStep(): LifecycleStep =
    LifecycleStep(
      scope = scope,
      type = type,
      id = id,
      status = status,
      text = text,
      link = link,
      startTime = timestamp
      // if we're using this, it's the first event we have,
      // so the timestamp will be the start time
    )
}
