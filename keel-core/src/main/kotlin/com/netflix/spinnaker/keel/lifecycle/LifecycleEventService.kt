package com.netflix.spinnaker.keel.lifecycle

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import org.springframework.stereotype.Component

@Component
class LifecycleEventService (
  val lifecycleEventRepository: LifecycleEventRepository
) {
  fun getStepsForArtifactAndVersion(deliveryArtifact: DeliveryArtifact, version: String): List<LifecycleStep> =
    lifecycleEventRepository.getSteps(deliveryArtifact, version)
}
