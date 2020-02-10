package com.netflix.spinnaker.keel.json.mixins

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY
import com.netflix.spinnaker.keel.api.VersioningStrategy

internal interface DeliveryArtifactMixin {
  @get:JsonProperty(access = WRITE_ONLY)
  val deliveryConfigName: String

  @get:JsonProperty(access = WRITE_ONLY)
  val versioningStrategy: VersioningStrategy
}
