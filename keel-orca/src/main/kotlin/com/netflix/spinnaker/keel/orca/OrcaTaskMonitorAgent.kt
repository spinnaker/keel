package com.netflix.spinnaker.keel.orca

import com.netflix.spinnaker.keel.events.TaskCreatedEvent
import com.netflix.spinnaker.keel.persistence.TaskTrackingRepository
import java.util.concurrent.Executors
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
) {

  @EventListener(TaskCreatedEvent::class)
  fun onTaskEvent(event: TaskCreatedEvent) {
    taskTrackingRepository.store(event.taskRecord)
  }
  // TODO: nuild actual logic of the agent - ignore this file for now
  // TODO: check about the DiscoveryActivated thing
  private val log by lazy { LoggerFactory.getLogger(javaClass) }
  private val executorService = Executors.newSingleThreadScheduledExecutor()
//  private val lastResourceCheck: AtomicReference<Instant> = createDriftGauge("bla")
//  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())
}
