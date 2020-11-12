package com.netflix.spinnaker.keel.lifecycle

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact

abstract class LifecycleEvent(
  val scope: LifecycleEventScope,
  val artifact: DeliveryArtifact,
  val artifactVersion: String,
  val type: LifecycleEventType,
  val id: String?,
  val status: LifecycleEventStatus
)
