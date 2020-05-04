package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.serviceAccount
import com.netflix.spinnaker.keel.pause.ActuationPauser
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import java.time.Clock
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ResourceHistoryListener(
  private val resourceRepository: ResourceRepository,
  private val actuationPauser: ActuationPauser,
  private val clock: Clock
) {

  @EventListener(ResourceEvent::class)
  fun onResourceEvent(event: ResourceEvent) {
    resourceRepository.appendHistory(event)

    // Account for the case where a resource was paused, then deleted (i.e. removed from management), then
    // resubmitted, where we don't want to inadvertently resume actuation without the user knowing and giving
    // explicit consent, by injecting a ResourceActuationPaused event.
    // ApplicationActuationPaused events are injected in the resource event history dynamically.
    if (event is ResourceCreated && actuationPauser.resourceIsPaused(event.id)) {
      val resource = resourceRepository.get(event.id)
      resourceRepository.appendHistory(ResourceActuationPaused(resource, resource.serviceAccount, clock))
    }
  }
}
