package com.netflix.spinnaker.keel.actuation

import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec

interface ArtifactHandler {
  val name: String
    get() = javaClass.simpleName

  suspend fun handle(artifact: ArtifactSpec)
}
