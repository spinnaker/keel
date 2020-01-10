package com.netflix.spinnaker.keel.orca

import com.netflix.spectator.api.Id
import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.patterns.PolledMeter
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference


@Component
class OrcaTaskMonitorAgent (
  val spectator: Registry,
  private val clock: Clock,
  private val resourceRepository: ResourceRepository,
  private val orcaService: OrcaService,
  private val publisher: ApplicationEventPublisher
) {
  //TODO: continue implementing this - ignor this file for now
  //TODO: check about the DiscoveryActivated thing
  private val log by lazy { LoggerFactory.getLogger(javaClass) }
  private val executorService = Executors.newSingleThreadScheduledExecutor()
//  private val lastResourceCheck: AtomicReference<Instant> = createDriftGauge("bla")
  private val _lastAgentRun = AtomicReference<Instant>(clock.instant())

  private val lastRun: Id = spectator.createId("orca.agent")


  private fun monitorOrcaTasks () {
    initClock()

  }

  fun initClock () {
    _lastAgentRun.set(clock.instant())
  }

}
