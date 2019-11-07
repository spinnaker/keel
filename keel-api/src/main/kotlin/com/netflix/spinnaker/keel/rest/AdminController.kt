package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.NoSuchResourceException
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import org.springframework.http.HttpStatus.NOT_FOUND
import org.slf4j.LoggerFactory.getLogger
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping(path = ["/admin"])
class AdminController(
  private val deliveryConfigRepository: DeliveryConfigRepository,
  private val resourceRepository: ResourceRepository
) {
  private val log by lazy { getLogger(javaClass) }

  @DeleteMapping(
    path = ["/{application}"]
  )
  fun deleteApplicationData(
    @PathVariable("application") application: String
  ) {
    log.debug("Deleting all data for application: $application")
    resourceRepository.deleteByApplication(application)
    return deliveryConfigRepository.deleteByApplication(application)
  }

  @ExceptionHandler(NoSuchResourceException::class)
  @ResponseStatus(NOT_FOUND)
  fun onNotFound(e: NoSuchResourceException) {
    log.error(e.message)
  }
}
