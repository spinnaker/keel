package com.netflix.spinnaker.keel.bakery.api

import com.netflix.spinnaker.keel.constraints.ConstraintStateAttributes

data class BakeConstraintAttributes(
  val executionId: String? = null
) : ConstraintStateAttributes("bake")
