package com.netflix.spinnaker.keel.apidocs

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import com.netflix.spinnaker.keel.api.support.extensionsOf
import org.springframework.stereotype.Component

@Component
class DeliveryArtifactModelConverter(
  private val extensionRegistry: ExtensionRegistry
) : SubtypesModelConverter<DeliveryArtifact>(DeliveryArtifact::class.java) {

  override val discriminator: String? = DeliveryArtifact::type.name

  override val subTypes: List<Class<out DeliveryArtifact>>
    get() = mapping.values.toList()

  override val mapping: Map<String, Class<out DeliveryArtifact>>
    get() = extensionRegistry.extensionsOf()
}
