package com.netflix.spinnaker.keel.jackson.mixins

import com.fasterxml.jackson.annotation.JsonValue

internal interface SortStrategyMixin {
  @get:JsonValue
  val type: String
}