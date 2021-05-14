package com.netflix.spinnaker.keel.igor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.netflix.spinnaker.config.BaseUrlConfig
import com.netflix.spinnaker.config.LifecycleConfig
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.front50.Front50Service
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
import com.netflix.spinnaker.keel.retrofit.isNotFound
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import retrofit2.HttpException
import java.time.Instant

/**
 * A monitor for build status that reads artifact metadata from an external system,
 * looks at the build data from that status, and emits events according to that status.
 */
@Component
@EnableConfigurationProperties(LifecycleConfig::class, BaseUrlConfig::class)
class BuildLifecycleMonitor(
  override val monitorRepository: LifecycleMonitorRepository,
  override val publisher: ApplicationEventPublisher,
  override val lifecycleConfig: LifecycleConfig,
  val objectMapper: ObjectMapper,
  val artifactMetadataService: ArtifactMetadataService,
  val front50Service: Front50Service?,
  val baseUrlConfig: BaseUrlConfig
) : LifecycleMonitor(monitorRepository, publisher, lifecycleConfig) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun handles(type: LifecycleEventType): Boolean =
    type == BUILD

  override suspend fun monitor(task: MonitoredTask) {
    val buildData = parseAndValidate(task) ?: return
    runCatching {
      if (buildData.buildMetadata == null || !buildData.buildMetadata.isComplete()) {
        artifactMetadataService.getArtifactMetadata(buildData.buildNumber, buildData.commitId)?.buildMetadata
      } else {
        // no need to get fresh info, we're done monitoring.
        buildData.buildMetadata
      }
    }.onSuccess { buildMetadata ->
      if (buildMetadata == null) {
        log.error("Error fetching status for $task, response was null")
        handleFailureFetchingStatus(task)
      } else {
        when (buildMetadata.status) {
          "BUILDING" -> publishRunningEvent(task)
          "SUCCESS" -> publishSucceededEvent(task, buildMetadata)
          "FAILURE" -> publishFailedEvent(task, buildMetadata)
          "ABORTED" -> publishAbortedEvent(task, buildMetadata)
          // UNSTABLE means build passed but tests failed (might need to reevaluate success status choice in the future)
          "UNSTABLE" -> publishSucceededEvent(task, buildMetadata)
          else -> publishUnknownStatusEvent(task, buildMetadata.status)
        }

        if (buildMetadata.isComplete()) {
          endMonitoringOfTask(task)
        } else {
          markSuccessFetchingStatus(task)
        }
      }
    }
      .onFailure { exception ->
        log.error("Error fetching status for $task: ", exception)
        handleFailureFetchingStatus(task)
      }
  }

  /**
   * Parses build data if format is correct, otherwise publishes an Unknown event
   * and end monitoring of this task.
   */
  private fun parseAndValidate(task: MonitoredTask): BuildData? =
    try {
      parseBuildData(task)
    } catch (e: IllegalArgumentException) {
      publishUnknownEvent(task)
      endMonitoringOfTask(task)
      null
   }

  private fun parseBuildData(task: MonitoredTask): BuildData =
      objectMapper.convertValue(task.triggeringEvent.data)

  private fun publishRunningEvent(task: MonitoredTask) {
    task.publishEvent(
      RUNNING,
      "Build running for version ${task.triggeringEvent.artifactVersion}",
    )
  }

  private fun publishSucceededEvent(task: MonitoredTask, buildMetadata: BuildMetadata) {
    task.publishEvent(
      SUCCEEDED,
      "Build succeeded for version ${task.triggeringEvent.artifactVersion}",
      buildMetadata.completedAtInstant
    )
  }

  private fun publishFailedEvent(task: MonitoredTask, buildMetadata: BuildMetadata) {
    task.publishEvent(
      FAILED,
      "Build failed for version ${task.triggeringEvent.artifactVersion}",
      buildMetadata.completedAtInstant
    )
  }

  private fun publishAbortedEvent(task: MonitoredTask, buildMetadata: BuildMetadata) {
    task.publishEvent(
      ABORTED,
      "Build aborted for version ${task.triggeringEvent.artifactVersion}",
      buildMetadata.completedAtInstant
    )
  }

  private fun publishUnknownStatusEvent(task: MonitoredTask, status: String?) {
    log.warn("Unknown status $status while monitoring ${task.triggeringEvent}")
    task.publishEvent(
      UNKNOWN,
      "Build status unknown for version ${task.triggeringEvent.artifactVersion}",
    )
  }

  private fun publishUnknownEvent(task: MonitoredTask) {
    log.warn("Unable to monitor build for ${task.triggeringEvent} because at least one required data field is missing")
    task.publishEvent(
      UNKNOWN,
      "Build status unknown for version ${task.triggeringEvent.artifactVersion}",
    )
  }

  override fun publishExceptionEvent(task: MonitoredTask) {
    task.publishEvent(
      UNKNOWN,
      "Failed to monitor build of version ${task.triggeringEvent.artifactVersion}" +
        " because we could not get the status ${lifecycleConfig.numFailuresAllowed} times. Status unknown.",
    )
  }

  fun MonitoredTask.publishEvent(status: LifecycleEventStatus, text: String, timestamp: Instant? = null) {
    publisher.publishEvent(triggeringEvent.copy(
      status = status,
      link = chooseLink(this),
      text = text,
      timestamp = timestamp,
      startMonitoring = false
    ))
  }

  fun chooseLink(task: MonitoredTask): String? {
    val buildData = parseBuildData(task)
    val app = runBlocking {
      try {
        front50Service?.applicationByName(buildData.application)
      } catch (e: HttpException) {
        if (e.isNotFound) {
          null
        } else {
          throw e
        }
      }
    }

    if (app == null || app.dataSources?.disabled?.contains("integration") == true) {
      // app not found or ci explicitly disabled
      return jenkinsLink(buildData)
    }

    // ci not explicitly disabled, check and see if ci view is configured
    return if (app.repoProjectKey != null && app.repoSlug != null && app.repoType != null) {
      buildUidToLink(task)
    } else {
      jenkinsLink(buildData)
    }
  }

  private fun buildUidToLink(task: MonitoredTask): String =
    "${baseUrlConfig.baseUrl}/#/applications/${task.triggeringEvent.data["application"]}/build/${task.link}"

  private fun jenkinsLink(buildData: BuildData): String? =
    buildData.buildMetadata?.job?.link ?: buildData.fallbackLink

  data class BuildData(
    val buildNumber: String,
    val commitId: String,
    val application: String,
    val buildMetadata: BuildMetadata?,
    val fallbackLink: String? // todo eb: for backwards compatibility, remove
  )
}
