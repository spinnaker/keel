package com.netflix.spinnaker.keel.titus.verification

import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.titus.TitusServerGroup.Location

data class ContainerTestVerification(
  val repository: String,
  val tag: String = "latest",
  val location: Location
) : Verification {
  override val type: String = "container-tests"
  override val id: String = "$repository/$tag"
}
