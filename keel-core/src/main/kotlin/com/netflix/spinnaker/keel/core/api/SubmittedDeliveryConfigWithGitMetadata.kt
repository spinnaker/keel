package com.netflix.spinnaker.keel.core.api

import com.netflix.spinnaker.keel.api.artifacts.GitMetadata

data class SubmittedDeliveryConfigWithGitMetadata(
  val config: SubmittedDeliveryConfig,
  val gitMetadata: GitMetadata? = null
)
