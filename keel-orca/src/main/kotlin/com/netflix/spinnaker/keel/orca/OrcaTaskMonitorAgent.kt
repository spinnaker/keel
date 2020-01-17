package com.netflix.spinnaker.keel.orca

import com.netflix.spinnaker.keel.activation.ApplicationDown
import com.netflix.spinnaker.keel.activation.ApplicationUp
import com.netflix.spinnaker.keel.events.TaskCreatedEvent
import com.netflix.spinnaker.keel.persistence.TaskTrackingRepository
import com.netflix.spinnaker.keel.scheduled.ScheduledAgent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

// This agent will run every minute, will fetch all the records from task_tracking table,
// and will publish the corresponding event [taskSucceeded / taskFailed] to the resource history log.

@Component
class OrcaTaskMonitorAgent(
  private val taskTrackingRepository: TaskTrackingRepository,
  private val orcaService: OrcaService,
  private val publisher: ApplicationEventPublisher
) : ScheduledAgent {

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

  override fun invoke() {
    // 1. Get active tasks from task tracking table
    // 2. get tasks status from orca
    // 3. publish event
  }

  // TODO: check about the DiscoveryActivated thing
  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
