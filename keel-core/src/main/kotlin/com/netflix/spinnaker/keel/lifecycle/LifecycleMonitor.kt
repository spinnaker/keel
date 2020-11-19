package com.netflix.spinnaker.keel.lifecycle

import com.netflix.spinnaker.config.LifecycleConfig
import com.netflix.spinnaker.keel.activation.ApplicationDown
import com.netflix.spinnaker.keel.activation.ApplicationUp
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.NOT_STARTED
import com.netflix.spinnaker.keel.telemetry.LifecycleMonitorLoadFailed
import com.netflix.spinnaker.keel.telemetry.LifecycleMonitorTimedOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Abstract lifecycle monitor that coordinates monitoring tasks while the
 * application is up.
 *
 * Implementations are responsible for doing the monitoring, emitting [LifecycleEvent]s
 * as the task transitions through different stages, updating the text / link to user
 * friendly things (w/in the [LifecycleEvent]s emitted), and calling [endMonitoringOf]
 * when the monitoring is finished.
 */
@EnableConfigurationProperties(LifecycleConfig::class)
abstract class LifecycleMonitor(
  open val monitorRepository: LifecycleMonitorRepository,
  open val publisher: ApplicationEventPublisher,
  open val lifecycleConfig: LifecycleConfig
) : CoroutineScope {
  override val coroutineContext: CoroutineContext = Dispatchers.IO

  private val enabled = AtomicBoolean(false)

  @EventListener(ApplicationUp::class)
  fun onApplicationUp() {
    log.info("Application up, enabling scheduled lifecycle monitoring")
    enabled.set(true)
  }

  @EventListener(ApplicationDown::class)
  fun onApplicationDown() {
    log.info("Application down, disabling scheduled lifecycle monitoring")
    enabled.set(false)
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  /**
   * @return true if this monitor can handle the event
   */
  abstract fun handles(event: LifecycleEvent): Boolean

  /**
   * @return the type of event this class can monitor
   */
  abstract fun typeHandled(): LifecycleEventType

  /**
   * Listens for a not started event that a subclass can handle, and saves that into
   * the database for monitoring.
   */
  @EventListener(LifecycleEvent::class)
  fun onLifecycleEvent(event: LifecycleEvent) {
    if (event.status == NOT_STARTED && handles(event) && event.link != null) {
      log.debug("${this.javaClass.simpleName} saving monitor event $event")
      monitorRepository.save(MonitoredTask(event, event.link))
    }
  }

  @Scheduled(fixedDelayString = "\${keel.lifecycle-monitor.frequency:PT1S}")
  fun invokeMonitoring() {
    if (enabled.get()) {

      val job: Job = launch {
        supervisorScope {
          runCatching {
            monitorRepository
              .tasksDueForCheck(typeHandled(), lifecycleConfig.minAgeDuration, lifecycleConfig.batchSize)
          }
            .onFailure {
              publisher.publishEvent(LifecycleMonitorLoadFailed(it))
            }
            .onSuccess { tasks ->
              tasks.forEach {task ->
                try {
                  /**
                   * Allow individual monitoring to timeout but catch the `CancellationException`
                   * to prevent the cancellation of all coroutines under [job]
                   */
                  withTimeout(lifecycleConfig.timeoutDuration.toMillis()) {
                    launch {
                      monitor(task)
                    }
                  }
                } catch (e: TimeoutCancellationException) {
                  log.error("Timed out monitoring task $task", e)
                  publisher.publishEvent(LifecycleMonitorTimedOut(task.type, task.link, task.triggeringEvent.artifactRef))
                }
              }
            }
        }
      }
      runBlocking { job.join() }
    }
  }

  /**
   * Checks the status of the task, and emits Lifecycle event
   * when the task is running, completed, or unknown.
   *
   * Removes the event from the repository when finished monitoring.
   */
  abstract suspend fun monitor(task: MonitoredTask)

  /**
   * Should be called from inside the [monitor] function when we are done
   * monitoring the task.
   */
  fun endMonitoringOf(task: MonitoredTask) {
    log.debug("${this.javaClass.simpleName} has completed monitoring for $task")
    monitorRepository.delete(task)
  }
}
