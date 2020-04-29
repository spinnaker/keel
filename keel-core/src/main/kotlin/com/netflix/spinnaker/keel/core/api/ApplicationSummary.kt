package com.netflix.spinnaker.keel.core.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder(value = ["name", "application", "serviceAccount", "apiVersion", "isPaused"])
data class ApplicationSummary(
  val name: String,
  val application: String,
  val serviceAccount: String,
  val apiVersion: String,
  val isPaused: Boolean = false
)
