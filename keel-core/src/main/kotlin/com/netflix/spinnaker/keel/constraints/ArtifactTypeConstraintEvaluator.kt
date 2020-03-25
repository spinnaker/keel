package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.core.api.ArtifactTypeConstraint
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class ArtifactTypeConstraintEvaluator(
  override val eventPublisher: ApplicationEventPublisher
) : ConstraintEvaluator<ArtifactTypeConstraint> {
  override val supportedType = SupportedConstraintType<ArtifactTypeConstraint>("artifact-type")

  override fun canPromote(artifact: DeliveryArtifact, version: String, deliveryConfig: DeliveryConfig, targetEnvironment: Environment): Boolean {
    val allowedTypes = mutableSetOf<ArtifactType>()
    deliveryConfig
      .environments
      .firstOrNull { it.name == targetEnvironment.name }
      ?.resources
      ?.forEach { resource ->
        val usedArtifact = resource.findAssociatedArtifact(deliveryConfig)
        if (usedArtifact != null) {
          allowedTypes.add(usedArtifact.type)
        }
      }

    return artifact.type in allowedTypes
  }
}
