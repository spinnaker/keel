package com.netflix.spinnaker.keel.jackson.mixins

import com.fasterxml.jackson.annotation.JsonValue

internal interface ArtifactSortByMethodMixin {
  @get:JsonValue
  val type: String
}