package com.netflix.spinnaker.keel.lifecycle

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import java.time.Instant

data class LifecycleEvent(
  val scope: LifecycleEventScope,
  val artifact: DeliveryArtifact,
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
      artifact = artifact,
      scope = scope,
      type = type,
      artifactVersion = artifactVersion,
      id = id,
      status = status,
      text = text,
      link = link,
      startTime = timestamp
      // if we're using this, it's the first event we have,
      // so the timestamp will be the start time
    )
}
