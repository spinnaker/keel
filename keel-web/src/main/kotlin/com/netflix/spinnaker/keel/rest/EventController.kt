package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.events.ResourceActuationPaused
import com.netflix.spinnaker.keel.events.ResourceEvent
import com.netflix.spinnaker.keel.pause.ResourcePauser
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository.Companion.DEFAULT_MAX_EVENTS
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import org.slf4j.LoggerFactory.getLogger
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/resources/events"])
class EventController(
  private val repository: KeelRepository,
  private val resourcePauser: ResourcePauser
) {
  private val log by lazy { getLogger(javaClass) }

  @GetMapping(
    path = ["/{id}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun eventHistory(
    @PathVariable("id") id: String,
    @RequestParam("limit") limit: Int?
  ): List<ResourceEvent> {
    log.debug("Getting state history for: $id")
    val resource = repository.getResource(id)
    val pauseScope = resourcePauser.getPauseScope(resource)
    return repository
      .resourceEventHistory(id, limit ?: DEFAULT_MAX_EVENTS)
      .also {
        if (pauseScope != null) {
          // val justBefore = it.indexOfFirst { event -> event.timestamp }
          // for user clarity we add a pause event to the resource history if the resource or parent app is paused.
          val events = it.toMutableList()
          events.add(
            0,
            pausedEvent(resource, "Resource actuation paused at the ${pauseScope.name.toLowerCase()} level")
          )
          return events
        }
      }
  }

  private fun pausedEvent(resource: Resource<*>, message: String) =
    ResourceActuationPaused(resource, message)
}
