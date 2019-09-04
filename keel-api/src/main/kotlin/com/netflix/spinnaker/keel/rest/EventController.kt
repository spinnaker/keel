package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.api.ResourceName
import com.netflix.spinnaker.keel.api.uid
import com.netflix.spinnaker.keel.events.ResourceEvent
import com.netflix.spinnaker.keel.persistence.NoSuchResourceException
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import org.slf4j.LoggerFactory.getLogger
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Period

@RestController
@RequestMapping(path = ["/resources/events"])
class EventController(
  private val resourceRepository: ResourceRepository
) {
  private val log by lazy { getLogger(javaClass) }

  @GetMapping(
    path = ["/{name}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun eventHistory(
    @PathVariable("name") name: ResourceName,
    @RequestParam("maxAge", defaultValue = "P3D") maxAge: Period,
    @RequestParam("limit", defaultValue = "0") limit: Int
  ): List<ResourceEvent> {
    log.debug("Getting state history for: $name")
    return resourceRepository.get(name).let { resource ->
      resourceRepository
        .eventHistory(resource.uid, maxAge, limit)
    }
  }

  // TODO: would be nice to make this common with ResourceController
  @ExceptionHandler(NoSuchResourceException::class)
  @ResponseStatus(NOT_FOUND)
  fun onNotFound(e: NoSuchResourceException) {
    log.error(e.message)
  }
}
