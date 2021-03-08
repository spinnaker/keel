package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.constraints.ConstraintStateAttributes

data class DependsOnConstraintAttributes(
  val dependsOnEnvironment: String
) : ConstraintStateAttributes("depends-on")
