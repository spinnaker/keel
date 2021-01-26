package com.netflix.spinnaker.keel.actuation

import com.netflix.spinnaker.keel.activation.ApplicationDown
import com.netflix.spinnaker.keel.activation.ApplicationUp
import com.netflix.spinnaker.keel.persistence.AgentLockRepository
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.telemetry.AgentInvocationComplete
import com.netflix.spinnaker.keel.telemetry.ArtifactCheckComplete
import com.netflix.spinnaker.keel.telemetry.ArtifactCheckTimedOut
import com.netflix.spinnaker.keel.telemetry.EnvironmentsCheckTimedOut
import com.netflix.spinnaker.keel.telemetry.ResourceCheckCompleted
import com.netflix.spinnaker.keel.telemetry.ResourceCheckTimedOut
import com.netflix.spinnaker.keel.telemetry.ResourceLoadFailed
import com.netflix.spinnaker.keel.telemetry.VerificationCheckComplete
import com.netflix.spinnaker.keel.telemetry.VerificationTimedOut
import com.netflix.spinnaker.keel.verification.VerificationRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

@Component
class CheckScheduler(
  private val repository: KeelRepository,
  private val resourceActuator: ResourceActuator,
  private val environmentPromotionChecker: EnvironmentPromotionChecker,
  private val verificationRunner: VerificationRunner,
  private val artifactHandlers: Collection<ArtifactHandler>,
  @Value("\${keel.resource-check.min-age-duration:60s}") private val resourceCheckMinAge: Duration,
  @Value("\${keel.resource-check.batch-size:1}") private val resourceCheckBatchSize: Int,
  @Value("\${keel.resource-check.timeout-duration:2m}") private val checkTimeout: Duration,
  @Value("\${keel.environment-verification.min-age-duration:60s}") private val environmentVerificationMinAge: Duration,
  @Value("\${keel.environment-verification.batch-size:1}") private val environmentVerificationBatchSize: Int,
  private val publisher: ApplicationEventPublisher,
  private val agentLockRepository: AgentLockRepository,
  private val clock: Clock
) : CoroutineScope {
  override val coroutineContext: CoroutineContext = Dispatchers.IO

  private val enabled = AtomicBoolean(false)

  @EventListener(ApplicationUp::class)
  fun onApplicationUp() {
    log.info("Application up, enabling scheduled resource checks")
    enabled.set(true)
  }

  @EventListener(ApplicationDown::class)
  fun onApplicationDown() {
    log.info("Application down, disabling scheduled resource checks")
    enabled.set(false)
  }

  @Scheduled(fixedDelayString = "\${keel.resource-check.frequency:PT1S}")
  fun checkResources() {
    if (enabled.get()) {
      val startTime = clock.instant()
      val job = launch {
        supervisorScope {
          runCatching {
            repository
              .resourcesDueForCheck(resourceCheckMinAge, resourceCheckBatchSize)
          }
            .onFailure {
              publisher.publishEvent(ResourceLoadFailed(it))
            }
            .onSuccess {
              it.forEach {
                try {
                  /**
                   * Allow individual resource checks to timeout but catch the `CancellationException`
                   * to prevent the cancellation of all coroutines under [job]
                   */
                  withTimeout(checkTimeout.toMillis()) {
                    launch {
                      resourceActuator.checkResource(it)
                      publisher.publishEvent(ResourceCheckCompleted(Duration.between(startTime, clock.instant())))
                    }
                  }
                } catch (e: TimeoutCancellationException) {
                  log.error("Timed out checking resource ${it.id}", e)
                  publisher.publishEvent(ResourceCheckTimedOut(it.kind, it.id, it.application))
                }
              }
            }
        }
      }
      runBlocking { job.join() }
    }
  }

  @Scheduled(fixedDelayString = "\${keel.environment-check.frequency:PT1S}")
  fun checkEnvironments() {
    if (enabled.get()) {
      publisher.publishEvent(ScheduledEnvironmentCheckStarting)

      val job = launch {
        supervisorScope {
          repository
            .deliveryConfigsDueForCheck(resourceCheckMinAge, resourceCheckBatchSize)
            .forEach {
              try {
                /**
                 * Sets the timeout to (checkTimeout * environmentCount), since a delivery-config's
                 * environments are checked sequentially within one coroutine job.
                 *
                 * TODO: consider refactoring environmentPromotionChecker so that it can be called for
                 *  individual environments, allowing fairer timeouts.
                 */
                withTimeout(checkTimeout.toMillis() * max(it.environments.size, 1)) {
                  launch { environmentPromotionChecker.checkEnvironments(it) }
                }
              } catch (e: TimeoutCancellationException) {
                log.error("Timed out checking environments for ${it.application}/${it.name}", e)
                publisher.publishEvent(EnvironmentsCheckTimedOut(it.application, it.name))
              } finally {
                repository.markDeliveryConfigCheckComplete(it)
              }
            }
        }
      }

      runBlocking { job.join() }
    }
  }

  @Scheduled(fixedDelayString = "\${keel.artifact-check.frequency:PT1S}")
  fun checkArtifacts() {
    if (enabled.get()) {
      val startTime = clock.instant()
      publisher.publishEvent(ScheduledArtifactCheckStarting)
      val job = launch {
        supervisorScope {
          repository.artifactsDueForCheck(resourceCheckMinAge, resourceCheckBatchSize)
            .forEach { artifact ->
              try {
                withTimeout(checkTimeout.toMillis()) {
                  launch {
                    artifactHandlers.forEach { handler ->
                      handler.handle(artifact)
                    }
                  }
                }
              } catch (e: TimeoutCancellationException) {
                log.error("Timed out checking artifact $artifact from ${artifact.deliveryConfigName}", e)
                publisher.publishEvent(ArtifactCheckTimedOut(artifact.name, artifact.deliveryConfigName))
              }
            }
        }
      }
      runBlocking { job.join() }
      publisher.publishEvent(ArtifactCheckComplete(Duration.between(startTime, clock.instant())))
    }
  }

  @Scheduled(fixedDelayString = "\${keel.environment-verification.frequency:PT1S}")
  fun verifyEnvironments() {
    if (enabled.get()) {
      val startTime = clock.instant()
      publisher.publishEvent(ScheduledEnvironmentVerificationStarting)

      val job = launch {
        supervisorScope {
          repository
            .nextEnvironmentsForVerification(environmentVerificationMinAge, environmentVerificationBatchSize)
            .forEach {
              try {
                withTimeout(checkTimeout.toMillis()) {
                  launch {
                    verificationRunner.runVerificationsFor(it)
                  }
                }
              } catch (e: TimeoutCancellationException) {
                log.error("Timed out verifying ${it.version} in ${it.deliveryConfig.application}/${it.environmentName}", e)
                publisher.publishEvent(VerificationTimedOut(it))
              }
            }
        }
      }

      runBlocking { job.join() }
      publisher.publishEvent(VerificationCheckComplete(Duration.between(startTime, clock.instant())))
    }
  }

  // todo eb: remove this loop in favor of transitioning the [OrcaTaskMonitoringAgent] to a
  //  [LifecycleMonitor]
  @Scheduled(fixedDelayString = "\${keel.scheduled.agent.frequency:PT1M}")
  fun invokeAgent() {
    if (enabled.get()) {
      val startTime = clock.instant()
      agentLockRepository.agents.forEach {
        val agentName: String = it.javaClass.simpleName
        val lockAcquired = agentLockRepository.tryAcquireLock(agentName, it.lockTimeoutSeconds)
        if (lockAcquired) {
          runBlocking {
            it.invokeAgent()
          }
          publisher.publishEvent(AgentInvocationComplete(Duration.between(startTime, clock.instant()), agentName))
        }
      }
    }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
