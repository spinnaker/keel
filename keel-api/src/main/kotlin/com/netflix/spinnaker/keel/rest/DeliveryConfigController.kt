package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.actuation.ResourcePersister
import com.netflix.spinnaker.keel.api.ConstraintState
import com.netflix.spinnaker.keel.api.ConstraintStatus
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.diff.AdHocDiffer
import com.netflix.spinnaker.keel.diff.EnvironmentDiff
import com.netflix.spinnaker.keel.exceptions.InvalidConstraintException
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository.Companion.DEFAULT_MAX_EVENTS
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping(path = ["/delivery-configs"])
class DeliveryConfigController(
  private val resourcePersister: ResourcePersister,
  private val deliveryConfigRepository: DeliveryConfigRepository,
  private val adHocDiffer: AdHocDiffer
) {
  @PostMapping(
    consumes = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun upsert(@RequestBody deliveryConfig: SubmittedDeliveryConfig): DeliveryConfig =
    resourcePersister.upsert(deliveryConfig)

  @GetMapping(
    path = ["/{name}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun get(@PathVariable("name") name: String): DeliveryConfig =
    deliveryConfigRepository.get(name)

  @PostMapping(
    path = ["/diff"],
    consumes = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun diff(@RequestBody deliveryConfig: SubmittedDeliveryConfig): List<EnvironmentDiff> =
    adHocDiffer.calculate(deliveryConfig)

  @GetMapping(
    path = ["/{name}/environment/{environment}/constraints"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun getConstraintState(
    @PathVariable("name") name: String,
    @PathVariable("environment") environment: String,
    @RequestParam("limit") limit: Int?
  ): List<ConstraintState> =
    deliveryConfigRepository.constraintStateFor(name, environment, limit ?: DEFAULT_MAX_EVENTS)

  @PostMapping(
    path = ["/{name}/environment/{environment}/constraint/{type}"],
    consumes = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  // TODO: This should be validated against write access to a service account. Service accounts should
  //  become a top-level property of either delivery configs or environments.
  fun updateConstraintStatus(
    @RequestHeader("X-SPINNAKER-USER") user: String,
    @PathVariable("name") name: String,
    @PathVariable("environment") environment: String,
    @PathVariable("type") type: String,
    @RequestParam("artifactVersion") artifactVersion: String,
    @RequestParam("status") status: String,
    @RequestParam("comment") comment: String?
  ) {
    val currentState = deliveryConfigRepository.getConstraintState(name, environment, artifactVersion, type)
      ?: throw InvalidConstraintException("$name/$environment/$type/$artifactVersion", "constraint not found")

    deliveryConfigRepository.storeConstraintState(
      currentState.copy(
        status = ConstraintStatus.valueOf(status),
        comment = comment ?: currentState.comment,
        judgedAt = Instant.now(),
        judgedBy = user
      ))
  }

  @ExceptionHandler(InvalidConstraintException::class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  fun onNotFound(e: InvalidConstraintException) {
    log.info(e.message)
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
