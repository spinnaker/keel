package com.netflix.spinnaker.keel.api.titus

import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.titus.TitusServerGroup.Location

data class TestContainerVerification(
  val image: String,
  val location: Location,
  val application: String? = null,
  val entrypoint: String? = null
) : Verification {
  override val type = TYPE
  override val id by lazy {
    "$image@${location.account}/${location.region}${entrypoint?.let { "[$it]" } ?: ""}"
  }

//  @get:JsonIgnore
  val imageId: String
    get() =
      if (image.contains(":")) image
      else "${image}:latest"

  companion object {
    const val TYPE = "test-container"
  }
}
