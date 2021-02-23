package com.netflix.spinnaker.keel.api.titus

import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.titus.TitusServerGroup.Location

data class TestContainerVerification(
  val image: String? = null,

  // These two fields are deprecated, replaced by image. Will remove once we move everyone off them
  val repository: String? = null,
  val tag: String? = "latest",

  val location: Location,
  val application: String? = null
) : Verification {
  override val type = TYPE
  override val id = "$repository:$tag"

  companion object {
    const val TYPE = "test-container"
  }
}
