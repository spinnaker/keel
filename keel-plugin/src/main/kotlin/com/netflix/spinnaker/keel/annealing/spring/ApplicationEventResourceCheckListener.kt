package com.netflix.spinnaker.keel.annealing.spring

import com.netflix.spinnaker.keel.annealing.ResourceActuator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async

open class ApplicationEventResourceCheckListener(
  private val resourceActuator: ResourceActuator
) {
  private val scope: CoroutineScope
    get() = CoroutineScope(IO)

  @Async
  @EventListener(ResourceCheckEvent::class)
  open fun onResourceCheck(event: ResourceCheckEvent) {
    with(event) {
      log.debug("Received request to check {}", name)
      scope.launch {
        resourceActuator.checkResource(name, apiVersion, kind)
      }
    }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
