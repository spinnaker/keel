package com.netflix.spinnaker.keel.core.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.netflix.spinnaker.keel.api.DeliveryConfig

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder(value = ["name", "application", "serviceAccount", "apiVersion", "paused"])
data class ApplicationSummary(
  @JsonIgnore val config: DeliveryConfig,
  val isPaused: Boolean = false
) {
  val name: String
    get() = config.name
  val application: String
    get() = config.application
  val serviceAccount: String
    get() = config.serviceAccount
  val apiVersion: String
    get() = config.apiVersion
}
