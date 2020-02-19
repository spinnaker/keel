package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact

interface ArtifactVersioned {
  val deliveryArtifact: DeliveryArtifact?
  val artifactVersion: String?
}
