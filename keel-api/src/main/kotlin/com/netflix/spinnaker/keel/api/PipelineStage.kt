package com.netflix.spinnaker.keel.api

interface PipelineStage {
  val type: String
  val name: String
}
