package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.events.ApplicationActuationPaused
import com.netflix.spinnaker.keel.events.ResourceActuationPaused
import com.netflix.spinnaker.keel.events.ResourceEvent
import com.netflix.spinnaker.keel.pause.ActuationPauser
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
  private val actuationPauser: ActuationPauser
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
    val events = repository
      .resourceEventHistory(id, limit ?: DEFAULT_MAX_EVENTS)
      .toMutableList()

    // For user clarity we add a pause event to the resource history for every pause event from the parent app.
    // We do this dynamically here so that it applies to all resources in the app, even those added _after_ the
    // application was paused.
    val appPausedEvents = repository
      .applicationEventHistory(resource.application, events.last().timestamp)
      .filterIsInstance<ApplicationActuationPaused>()

    appPausedEvents.forEach { appPaused ->
      val lastBeforeAppPaused = events.indexOfFirst { event ->
        event.timestamp.isBefore(appPaused.timestamp)
      }

      if (lastBeforeAppPaused == -1) {
        log.warn("Unable to find a resource event just before application paused event at ${appPaused.timestamp}")
      } else {
        events.add(
          lastBeforeAppPaused,
          ResourceActuationPaused(resource, "Resource actuation paused at the application level",
            appPaused.timestamp)
        )
      }
    }

    return events
  }
}
