package com.netflix.spinnaker.keel.core.api

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import java.time.Instant

data class EnvironmentArtifactPin(
  val targetEnvironment: String,
  val reference: String,
  val version: String,
  val pinnedBy: String?,
  val comment: String?
) {
  init {
    if (comment != null) {
      require(comment.length <= 255) {
        "Comments should have a maximum length of 255 characters."
      }
    }
  }
}

data class PinnedEnvironment(
  val deliveryConfigName: String,
  val targetEnvironment: String,
  val artifact: DeliveryArtifact,
  val version: String,
  val pinnedBy: String?,
  val pinnedAt: Instant?,
  val comment: String?
) {
  init {
    if (comment != null) {
      require(comment.length <= 255) {
        "Comments should have a maximum length of 255 characters."
      }
    }
  }
}
