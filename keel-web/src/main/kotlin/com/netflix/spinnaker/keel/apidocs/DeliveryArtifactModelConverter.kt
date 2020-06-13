package com.netflix.spinnaker.keel.apidocs

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.artifact.DEB
import com.netflix.spinnaker.keel.artifact.DOCKER
import com.netflix.spinnaker.keel.artifact.DebianArtifact
import com.netflix.spinnaker.keel.artifact.DockerArtifact
import org.springframework.stereotype.Component

@Component
class DeliveryArtifactModelConverter : SubtypesModelConverter<DeliveryArtifact>(DeliveryArtifact::class.java) {
  override val subTypes = listOf(
    DebianArtifact::class.java,
    DockerArtifact::class.java
  )

  override val discriminator: String? = DeliveryArtifact::type.name

  // TODO: can we just work this out automatically?
  override val mapping: Map<String, Class<out DeliveryArtifact>> = mapOf(
    DEB to DebianArtifact::class.java,
    DOCKER to DockerArtifact::class.java
  )
}
