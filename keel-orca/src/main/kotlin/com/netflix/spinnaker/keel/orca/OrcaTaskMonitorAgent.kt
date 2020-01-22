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

// This agent will run every minute, will fetch all the records from task_tracking table,
// and will publish the corresponding event [taskSucceeded / taskFailed] to the resource history log.

@Component
class OrcaTaskMonitorAgent(
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceRepository: ResourceRepository,
  private val orcaService: OrcaService,
  private val publisher: ApplicationEventPublisher,
  private val clock: Clock
) : ScheduledAgent {

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
  // 2. get tasks status from orca
  // 3. publish event
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
        .map { (resourceId, taskDetails) ->
          val taskStatus = taskDetails.status
          if (!taskStatus.isIncomplete()) {
            if (taskStatus.isSuccess()) {
              publisher.publishEvent(
                ResourceActuationSucceeded(resourceRepository.get(
                  ResourceId(resourceId)), clock))
            }
            if (taskStatus.isFailure()) {
              // TODO: fetch the actual failure
              publisher.publishEvent(
                ResourceActuationFailed(resourceRepository.get(ResourceId(resourceId)), "", clock))
            }
            taskTrackingRepository.delete(taskDetails.id)
          }
        }
    }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
