package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType.deb
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType.docker
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType.valueOf
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.events.ArtifactEvent
import com.netflix.spinnaker.keel.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.events.ArtifactSyncEvent
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import com.netflix.spinnaker.kork.artifacts.model.Artifact
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus.ACCEPTED
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/artifacts"])
class ArtifactController(
  private val publisher: ApplicationEventPublisher,
  private val artifactRepository: ArtifactRepository,
  private val deliveryConfigRepository: DeliveryConfigRepository
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @PostMapping(
    path = ["/events"],
    consumes = [APPLICATION_JSON_VALUE]
  )
  @ResponseStatus(ACCEPTED)
  fun submitArtifact(@RequestBody echoArtifactEvent: EchoArtifactEvent) {
    echoArtifactEvent.payload.artifacts.forEach { artifact ->
      if (artifact.type.equals(deb.toString(), true) && artifact.isFromArtifactEvent()) {
        publisher.publishEvent(ArtifactEvent(listOf(artifact), emptyMap()))
      } else if (artifact.type.equals(docker.toString(), true)) {
        publisher.publishEvent(ArtifactEvent(listOf(artifact), emptyMap()))
      } else {
        log.debug("Ignoring artifact event with type {}: {}", artifact.type, artifact)
      }
    }
  }

  @PostMapping(
    path = ["/register"],
    consumes = [APPLICATION_JSON_VALUE]
  )
  @ResponseStatus(ACCEPTED)
  fun register(@RequestBody artifact: DeliveryArtifact) {
    publisher.publishEvent(ArtifactRegisteredEvent(artifact))
  }

  @PostMapping(
    path = ["/sync"]
  )
  @ResponseStatus(ACCEPTED)
  fun sync() {
    publisher.publishEvent(ArtifactSyncEvent(true))
  }

  @PostMapping(
    path = ["/pin"]
  )
  @ResponseStatus(ACCEPTED)
  fun pin(
    @RequestHeader("X-SPINNAKER-USER") user: String,
    @RequestBody pin: EnvironmentArtifactPin
  ) {
    checkNotNull(pin.version) {
      "A version to pin is required."
    }

    val deliveryConfig = deliveryConfigRepository.get(pin.deliveryConfigName)
    artifactRepository.pinEnvironment(deliveryConfig, pin.copy(pinnedBy = user))
  }

  @DeleteMapping(
    path = ["/pin"]
  )
  @ResponseStatus(ACCEPTED)
  fun deletePin(@RequestBody pin: EnvironmentArtifactPin) {
    val deliveryConfig = deliveryConfigRepository.get(pin.deliveryConfigName)
    artifactRepository.deletePin(deliveryConfig, pin.targetEnvironment, pin.reference, valueOf(pin.type.toUpperCase()))
  }

  @DeleteMapping(
    path = ["/pin/{deliveryConfig}/{targetEnvironment}"]
  )
  @ResponseStatus(ACCEPTED)
  fun deletePin(
    @PathVariable("deliveryConfig") deliveryConfigName: String,
    @PathVariable("targetEnvironment") targetEnvironment: String
  ) {
    val deliveryConfig = deliveryConfigRepository.get(deliveryConfigName)
    artifactRepository.deletePin(deliveryConfig, targetEnvironment)
  }

  @GetMapping(
    path = ["/{name}/{type}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun listVersions(
    @PathVariable name: String,
    @PathVariable type: ArtifactType
  ): List<String> =
    artifactRepository.versions(name, type)

  // Debian Artifacts should contain a releaseStatus in the metadata
  private fun Artifact.isFromArtifactEvent() =
    this.metadata.containsKey("releaseStatus") && this.metadata["releaseStatus"] != null
}

data class EchoArtifactEvent(
  val payload: ArtifactEvent,
  val eventName: String
)
