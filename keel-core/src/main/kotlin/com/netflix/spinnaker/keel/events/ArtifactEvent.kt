package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.ArtifactType
import com.netflix.spinnaker.kork.artifacts.model.Artifact

data class ArtifactEvent(
  val artifacts: List<Artifact>,
  val details: Map<String, Any>?
)

data class ArtifactRegisteredEvent(
  val name: String,
  val type: ArtifactType
)
