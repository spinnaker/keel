package com.netflix.spinnaker.keel.jackson.mixins

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy

internal interface DeliveryArtifactMixin {
  @get:JsonProperty(access = WRITE_ONLY)
  val deliveryConfigName: String

  @get:JsonProperty(access = WRITE_ONLY)
  val versioningStrategy: VersioningStrategy

  @get:JsonIgnore
  val filteredByBranch: Boolean

  @get:JsonIgnore
  val filteredByPullRequest: Boolean

  @get:JsonIgnore
  val filteredByReleaseStatus: Boolean
}
