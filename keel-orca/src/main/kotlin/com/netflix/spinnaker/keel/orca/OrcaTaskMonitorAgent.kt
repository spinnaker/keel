package com.netflix.spinnaker.keel.orca

import com.netflix.spinnaker.keel.activation.ApplicationDown
import com.netflix.spinnaker.keel.activation.ApplicationUp
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.events.ResourceActuationFailed
import com.netflix.spinnaker.keel.events.ResourceActuationSucceeded
import com.netflix.spinnaker.keel.events.TaskCreatedEvent
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.TaskTrackingRepository
import com.netflix.spinnaker.keel.scheduled.ScheduledAgent
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class OrcaTaskMonitorAgent(
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceRepository: ResourceRepository,
  private val orcaService: OrcaService,
  private val publisher: ApplicationEventPublisher,
  private val clock: Clock
) : ScheduledAgent {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  // TODO gyardeni: check how long
  override val lockTimeoutSeconds = TimeUnit.MINUTES.toSeconds(1)

  private var enabled = false

  @EventListener(ApplicationUp::class)
  fun onApplicationUp() {
    log.info("Application up, enabling scheduled resource checks")
    enabled = true
  }

  @EventListener(ApplicationDown::class)
  fun onApplicationDown() {
    log.info("Application down, disabling scheduled resource checks")
    enabled = false
  }

  @EventListener(TaskCreatedEvent::class)
  fun onTaskEvent(event: TaskCreatedEvent) {
    taskTrackingRepository.store(event.taskRecord)
  }

  // 1. Get active tasks from task tracking table
  // 2. For each task, call orca and ask for details
  // 3. For each completed task, will emit an event for success/failure
  override suspend fun invokeAgent() {
    coroutineScope {
      taskTrackingRepository.getTasks()
        .associate {
          it.subject to
            async {
              orcaService.getOrchestrationExecution(it.id)
            }
        }
        .mapValues { it.value.await() }
        .filterValues { it.status.isComplete() }
        .map { (resourceId, taskDetails) ->
          when (taskDetails.status.isSuccess()) {
            true -> publisher.publishEvent(
              ResourceActuationSucceeded(
                resourceRepository.get(ResourceId(resourceId)), clock))
            false -> publisher.publishEvent(
              // TODO: fetch the actual failure message
              ResourceActuationFailed(
                resourceRepository.get(ResourceId(resourceId)), "", clock))
          }
          taskTrackingRepository.delete(taskDetails.id)
        }
    }
  }
}
