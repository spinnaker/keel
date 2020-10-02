package com.netflix.spinnaker.keel.persistence

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

abstract class UnhealthyVetoRepository(
  open val clock: Clock
) {
  val log by lazy { LoggerFactory.getLogger(javaClass) }

  init {
    log.info("Using ${javaClass.simpleName}")
  }

  /**
   * @return true if we don't have an unhealthy record, or if a resource has
   * been marked as unhealthy only once.
   *
   * We want to see a bit of sustained unhealthy duration before we
   * take any action.
   */
  abstract fun isHealthy(
    resourceId: String
  ): Boolean

  abstract fun markUnhealthy(
    resourceId: String,
    application: String
  )

  abstract fun markAllowed(
    resourceId: String
  )

  abstract fun getLastAllowedTime(
    resourceId: String
  ) : Instant?

  abstract fun getNoticedTime(
    resourceId: String
  ) : Instant?

  /**
   * Clears unhealthy marking for [resourceId]
   */
  abstract fun markHealthy(resourceId: String)

  /**
   * Returns all currently vetoed resources
   */
  abstract fun getAll(): Set<String>

  /**
   * Returns all currently vetoed resources for an [application]
   */
  abstract fun getAllForApp(application: String): Set<String>
}
