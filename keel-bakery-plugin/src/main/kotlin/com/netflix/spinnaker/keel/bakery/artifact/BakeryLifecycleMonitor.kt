package com.netflix.spinnaker.keel.bakery.artifact

import com.netflix.spinnaker.config.LifecycleConfig
import com.netflix.spinnaker.keel.core.api.DEFAULT_SERVICE_ACCOUNT
import com.netflix.spinnaker.keel.lifecycle.LifecycleEvent
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType.BAKE
import com.netflix.spinnaker.keel.lifecycle.LifecycleMonitor
import com.netflix.spinnaker.keel.lifecycle.LifecycleMonitorRepository
import com.netflix.spinnaker.keel.lifecycle.MonitoredTask
import com.netflix.spinnaker.keel.orca.ExecutionDetailResponse
import com.netflix.spinnaker.keel.orca.OrcaExecutionStatus.BUFFERED
import com.netflix.spinnaker.keel.orca.OrcaExecutionStatus.RUNNING
import com.netflix.spinnaker.keel.orca.OrcaService
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.vavr.control.Try
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@EnableConfigurationProperties(LifecycleConfig::class)
class BakeryLifecycleMonitor(
  override val monitorRepository: LifecycleMonitorRepository,
  override val publisher: ApplicationEventPublisher,
  override val lifecycleConfig: LifecycleConfig,
  private val orcaService: OrcaService,
  @Value("\${spinnaker.baseUrl}") private val spinnakerBaseUrl: String
): LifecycleMonitor(monitorRepository, publisher, lifecycleConfig) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun handles(event: LifecycleEvent): Boolean =
    event.type == BAKE

  override fun typeHandled(): LifecycleEventType = BAKE

  override suspend fun monitor(task: MonitoredTask) {
    kotlin.runCatching {
       orcaService.getOrchestrationExecution(task.link, DEFAULT_SERVICE_ACCOUNT)
    }
      .onSuccess { execution ->
        when {
          execution.status == BUFFERED -> publishCorrectLink(task)
          execution.status == RUNNING -> publishRunningEvent(task)
          execution.status.isSuccess() -> publishSucceededEvent(task)
          execution.status.isFailure() -> publishFailedEvent(task)
          else -> publishUnknownEvent(task, execution)
        }

        if (execution.status.isComplete()) {
          endMonitoringOf(task)
        }
      }
      .onFailure { exception ->
        log.error("Error fetching status for $task: ", exception)
        if (task.numFailures >= lifecycleConfig.numFailuresAllowed - 1) {
          log.warn("Too many errors (${lifecycleConfig.numFailuresAllowed}) " +
            "fetching the task status. Giving up.")
          publishExceptionEvent(task)
          monitorRepository.delete(task)
        } else {
          monitorRepository.markFailureGettingStatus(task)
        }
      }
  }

  private fun orcaTaskIdToLink(task: MonitoredTask): String =
    "$spinnakerBaseUrl/#/tasks/${task.link}"

  private fun publishCorrectLink(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      link = orcaTaskIdToLink(task)
    ))
  }

  private fun publishRunningEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = LifecycleEventStatus.RUNNING,
      link = orcaTaskIdToLink(task),
      text = "Bake running for version ${task.triggeringEvent.artifactVersion}"
    ))
  }
  private fun publishSucceededEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = LifecycleEventStatus.SUCCEEDED,
      link = orcaTaskIdToLink(task),
      text = "Bake succeeded for version ${task.triggeringEvent.artifactVersion}"
    ))
  }
  private fun publishFailedEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = LifecycleEventStatus.FAILED,
      link = orcaTaskIdToLink(task),
      text = "Bake failed for version ${task.triggeringEvent.artifactVersion}"
    ))
  }

  private fun publishExceptionEvent(task: MonitoredTask) {
    publisher.publishEvent(task.triggeringEvent.copy(
      status = LifecycleEventStatus.FAILED,
      link = orcaTaskIdToLink(task),
      text = "Failed to monitor bake of version ${task.triggeringEvent.artifactVersion}" +
        " because we could not get the status ${lifecycleConfig.numFailuresAllowed} times."
    ))
  }

  private fun publishUnknownEvent(task: MonitoredTask, execution: ExecutionDetailResponse) {
    log.warn("Monitored bake ${task.triggeringEvent} in an unhandled status (${execution.status}")
    publisher.publishEvent(task.triggeringEvent.copy(
      status = LifecycleEventStatus.UNKNOWN,
      link = orcaTaskIdToLink(task),
      text = "Bake status unknown for version ${task.triggeringEvent.artifactVersion}"
    ))
  }
}
