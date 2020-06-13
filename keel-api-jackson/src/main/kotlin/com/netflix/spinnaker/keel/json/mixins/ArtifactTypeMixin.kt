package com.netflix.spinnaker.keel.json.mixins

import com.fasterxml.jackson.annotation.JsonValue

internal interface ArtifactTypeMixin {
  @get:JsonValue
  val name: String
}
