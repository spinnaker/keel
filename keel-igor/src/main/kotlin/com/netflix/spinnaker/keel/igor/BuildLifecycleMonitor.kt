package com.netflix.spinnaker.keel.igor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.netflix.spinnaker.config.LifecycleConfig
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.FAILED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.RUNNING
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.SUCCEEDED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.UNKNOWN
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType.BUILD
import com.netflix.spinnaker.keel.lifecycle.LifecycleMonitor
import com.netflix.spinnaker.keel.lifecycle.LifecycleMonitorRepository
import com.netflix.spinnaker.keel.lifecycle.MonitoredTask
import com.netflix.spinnaker.keel.igor.artifact.ArtifactMetadataService
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.ABORTED
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
@EnableConfigurationProperties(LifecycleConfig::class)
class BuildLifecycleMonitor(
  override val monitorRepository: LifecycleMonitorRepository,
  override val publisher: ApplicationEventPublisher,
  override val lifecycleConfig: LifecycleConfig,
  val objectMapper: ObjectMapper,
  val artifactMetadataService: ArtifactMetadataService,
  @Value("\${spinnaker.baseUrl}") private val spinnakerBaseUrl: String
) : LifecycleMonitor(monitorRepository, publisher, lifecycleConfig) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun handles(type: LifecycleEventType): Boolean =
    type == BUILD

  override suspend fun monitor(task: MonitoredTask) {
    val buildData = parseBuildData(task) ?: return
    kotlin.runCatching {
      artifactMetadataService.getArtifactMetadata(buildData.buildNumber, buildData.commitId)
    }.onSuccess { metadata ->
      val buildMetadata = metadata?.buildMetadata
      if (buildMetadata == null) {
        log.error("Error fetching status for $task, response was null")
        handleFailureFetching(task)
      } else {
        when (buildMetadata.status) {
          "BUILDING" -> publishRunningEvent(task)
          "SUCCESS" -> publishSucceededEvent(task)
          "FAILURE" -> publishFailedEvent(task)
          "ABORTED" -> publishAbortedEvent(task)
          "UNSTABLE" -> publishSucceededEvent(task)
          else -> publishUnknownStatusEvent(task, buildMetadata.status)
        }

        if (buildMetadata.isComplete()) {
          endMonitoringOf(task)
        } else {
          markSuccessFetching(task)
        }
      }
    }
      .onFailure { exception ->
        log.error("Error fetching status for $task: ", exception)
        handleFailureFetching(task)
      }
  }

  /**
   * Parses build data if format is correct, otherwise publish an Unknown event
   * and end monitoring of this task.
   */
  private fun parseBuildData(task: MonitoredTask): BuildData? {
    try {
      return objectMapper.convertValue<BuildData>(task.triggeringEvent.data)
    } catch (e: IllegalArgumentException) {
      publishUnknownEvent(task)
      endMonitoringOf(task)
    }
    return null
  }

  private fun buildIdToLink(task: MonitoredTask): String =
    "$spinnakerBaseUrl/#/applications/${task.triggeringEvent.data["application"]}/builds/${task.link}/logs"

  private fun BuildMetadata.isComplete(): Boolean =
    status == "SUCCESS" || status == "FAILED" || status == "ABORTED"

  private fun publishRunningEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = RUNNING,
      link = buildIdToLink(task),
      text = "Build running for version ${task.triggeringEvent.artifactVersion}"
    ))
  }

  private fun publishSucceededEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = SUCCEEDED,
      link = buildIdToLink(task),
      text = "Build succeeded for version ${task.triggeringEvent.artifactVersion}"
    ))
  }

  private fun publishFailedEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = FAILED,
      link = buildIdToLink(task),
      text = "Build failed for version ${task.triggeringEvent.artifactVersion}"
    ))
  }

  private fun publishAbortedEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = ABORTED,
      link = buildIdToLink(task),
      text = "Build aborted for version ${task.triggeringEvent.artifactVersion}"
    ))
  }

  private fun publishUnknownStatusEvent(task: MonitoredTask, status: String?) {
    log.warn("Unknown status $status while monitoring ${task.triggeringEvent}")
    publisher.publishEvent(task.triggeringEvent.copy(
      status = UNKNOWN,
      link = buildIdToLink(task),
      text = "Build status unknown for version ${task.triggeringEvent.artifactVersion}"
    ))
  }

  private fun publishUnknownEvent(task: MonitoredTask) {
    log.warn("Unable to monitor build for ${task.triggeringEvent} because at least one required data field is missing")
    publisher.publishEvent(task.triggeringEvent.copy(
      status = UNKNOWN,
      link = buildIdToLink(task),
      text = "Build status unknown for version ${task.triggeringEvent.artifactVersion}"
    ))
  }

  override fun publishExceptionEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = UNKNOWN,
      link = buildIdToLink(task),
      text = "Failed to monitor build of version ${task.triggeringEvent.artifactVersion}" +
        " because we could not get the status ${lifecycleConfig.numFailuresAllowed} times. Status unknown."
    ))
  }

  data class BuildData(
    val buildNumber: String,
    val commitId: String,
    val application: String
  )
}
