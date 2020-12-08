package com.netflix.spinnaker.keel.titus.verification

import com.netflix.spinnaker.keel.api.Verification

data class ContainerTestVerification(
  val repository: String,
  val versionIdentifier: String = "latest"
) : Verification {
  override val type: String = "container-tests"
  override val id: String = "$repository/$versionIdentifier"
}
