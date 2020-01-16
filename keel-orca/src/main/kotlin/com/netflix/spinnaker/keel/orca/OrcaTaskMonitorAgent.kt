package com.netflix.spinnaker.keel.orca

import com.netflix.spinnaker.keel.activation.ApplicationDown
import com.netflix.spinnaker.keel.activation.ApplicationUp
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
  // @Value("\${keel.orca-task-check.min-age-duration:60m}") private val resourceCheckMinAgeDuration: Duration

) {

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

//  @Scheduled(fixedDelayString = "\${keel.orca-task-check.frequency:PT1M}")
//  fun checkTasks () {
//    if (enabled) {
//
//    } else {
//      log.debug("Scheduled orca task validation disabled")
//    }
//  }

  // TODO: check about the DiscoveryActivated thing
  private val log by lazy { LoggerFactory.getLogger(javaClass) }
  private val executorService = Executors.newSingleThreadScheduledExecutor()
//  private val lastResourceCheck: AtomicReference<Instant> = createDriftGauge("bla")
//  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())
}
