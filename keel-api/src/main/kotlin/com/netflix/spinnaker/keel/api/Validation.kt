package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.api.schema.Discriminator

interface Validation {
  @Discriminator
  val type: String
}
