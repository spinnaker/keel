package com.netflix.spinnaker.keel.artifact

import com.netflix.spinnaker.keel.activation.ApplicationDown
import com.netflix.spinnaker.keel.activation.ApplicationUp
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.SpinnakerArtifact
import com.netflix.spinnaker.keel.api.events.ArtifactEvent
import com.netflix.spinnaker.keel.api.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.api.events.ArtifactSyncEvent
import com.netflix.spinnaker.keel.api.plugins.ArtifactPublisher
import com.netflix.spinnaker.keel.api.plugins.supporting
import com.netflix.spinnaker.keel.core.comparator
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.telemetry.ArtifactVersionUpdated
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ArtifactListener(
  private val repository: KeelRepository,
  private val publisher: ApplicationEventPublisher,
  private val artifactPublishers: List<ArtifactPublisher<*>>
) {
  private val enabled = AtomicBoolean(false)

  @EventListener(ApplicationUp::class)
  fun onApplicationUp() {
    log.info("Application up, enabling scheduled artifact syncing")
    enabled.set(true)
  }

  @EventListener(ApplicationDown::class)
  fun onApplicationDown() {
    log.info("Application down, disabling scheduled artifact syncing")
    enabled.set(false)
  }

  @EventListener(ArtifactEvent::class)
  fun onArtifactEvent(event: ArtifactEvent) {
    log.debug("Received artifact event: {}", event)
    event
      .artifacts
      .filter { it.type.toLowerCase() in artifactTypeNames }
      .forEach { artifact ->
        if (repository.isRegistered(artifact.name, artifact.artifactType)) {
          val artifactPublisher = artifactPublishers.supporting(artifact.artifactType)
          val version = artifactPublisher.getFullVersionString(artifact)
          var status = artifactPublisher.getReleaseStatus(artifact)
          log.info("Registering version {} ({}) of {} {}", version, status, artifact.name, artifact.type)
          repository.storeArtifact(artifact.name, artifact.artifactType, version, status)
            .also { wasAdded ->
              if (wasAdded) {
                publisher.publishEvent(ArtifactVersionUpdated(artifact.name, artifact.artifactType))
              }
            }
        }
      }
  }

  /**
   * Fetch latest version of an artifact after it is registered.
   */
  @EventListener(ArtifactRegisteredEvent::class)
  fun onArtifactRegisteredEvent(event: ArtifactRegisteredEvent) {
    val artifact = event.artifact

    if (repository.artifactVersions(artifact).isEmpty()) {
      val artifactPublisher = artifactPublishers.supporting(artifact.type)
      val latestArtifact = runBlocking {
        artifactPublisher.getLatestArtifact(artifact)
      }
      val latestVersion = latestArtifact?.let { artifactPublisher.getFullVersionString(it) }
      if (latestVersion != null) {
        var status = artifactPublisher.getReleaseStatus(latestArtifact)
        log.debug("Storing latest version {} ({}) for registered artifact {}", latestVersion, status, artifact)
        repository.storeArtifact(artifact.name, artifact.type, latestVersion, status)
      } else {
        log.warn("No artifact versions found for ${artifact.type}:${artifact.name}")
      }
    }
  }

  @EventListener(ArtifactSyncEvent::class)
  fun triggerArtifactSync(event: ArtifactSyncEvent) {
    if (event.controllerTriggered) {
      log.info("Fetching latest version of all registered artifacts...")
    }
    syncArtifactVersions()
  }

  /**
   * For each registered debian artifact, get the last version, and persist if it's newer than what we have.
   */
  // todo eb: should we fetch more than one version?
  @Scheduled(fixedDelayString = "\${keel.artifact-refresh.frequency:PT6H}")
  fun syncArtifactVersions() {
    if (enabled.get()) {
      runBlocking {
        repository.getAllArtifacts().forEach { artifact ->
          launch {
            val lastRecordedVersion = getLatestStoredVersion(artifact)
            val artifactPublisher = artifactPublishers.supporting(artifact.type)
            val latestArtifact = artifactPublisher.getLatestArtifact(artifact)
            val latestVersion = latestArtifact?.let { artifactPublisher.getFullVersionString(it) }
            if (latestVersion != null) {
              val hasNew = when {
                lastRecordedVersion == null -> true
                latestVersion != lastRecordedVersion -> {
                  artifact.versioningStrategy.comparator.compare(lastRecordedVersion, latestVersion) > 0
                }
                else -> false
              }

              if (hasNew) {
                log.debug("Artifact {} has a missing version {}, persisting...", artifact, latestVersion)
                val status = artifactPublisher.getReleaseStatus(latestArtifact)
                repository.storeArtifact(artifact.name, artifact.type, latestVersion, status)
              }
            }
          }
        }
      }
    }
  }

  private fun getLatestStoredVersion(artifact: DeliveryArtifact): String? =
    repository.artifactVersions(artifact).sortedWith(artifact.versioningStrategy.comparator).firstOrNull()

  private val SpinnakerArtifact.artifactType: ArtifactType
    get() = artifactTypeNames.find { it == type.toLowerCase() }
      ?.let { type.toLowerCase() }
      ?: error("Unable to find registered artifact type for '$type'")

  private val artifactTypeNames by lazy {
    artifactPublishers.map { it.supportedArtifact.name }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
