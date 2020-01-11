package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.persistence.ResourceRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class DebugEventListener(private val resourceRepository: ResourceRepository) {

  @EventListener(DebugEvent::class)
  fun onDebugEvent(event: DebugEvent) {
    resourceRepository.appendDebugLog(event)
  }
}
