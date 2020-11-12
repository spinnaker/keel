package com.netflix.spinnaker.keel.lifecycle

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import org.springframework.stereotype.Component

@Component
class LifecycleEventService (
  val lifecycleEventRepository: LifecycleEventRepository
) {
  fun getEventsForArtifactAndVersion(deliveryArtifact: DeliveryArtifact, version: String): List<LifecycleEvent> =
    lifecycleEventRepository.getEvents(deliveryArtifact.toLifecycleEventId(), version)

}
