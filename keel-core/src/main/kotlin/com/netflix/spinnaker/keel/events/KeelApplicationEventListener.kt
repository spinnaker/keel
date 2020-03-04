package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.persistence.ResourceRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class KeelApplicationEventListener(private val resourceRepository: ResourceRepository) {
    @EventListener(KeelApplicationEvent::class)
    fun onResourceEvent(event: KeelApplicationEvent) {
      resourceRepository.appendHistory(event)
    }
  }
