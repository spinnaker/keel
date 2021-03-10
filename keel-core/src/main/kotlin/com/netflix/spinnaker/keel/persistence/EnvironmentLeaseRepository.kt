package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.Environment
import java.time.Instant

interface Lease {}

class ActiveLeaseExists(
  environment: Environment,
  holder: String,
  takenAt: Instant
) : Exception("Active lease exists on ${environment.name}: taken by $holder at $takenAt") {}


/**
 * A repository that allows you to take a lease (expiring lock) on an environment.
 *
 */
interface EnvironmentLeaseRepository {

  /**
   * Try to take a lease on an environment
   *
   * @returns a lease if nobody else has one
   *
   * @throws ActiveLeaseExists if someone else has a lease on the environment
   */
  fun tryAcquireLease(environment: Environment) : Lease

  /**
   * Release a held lease
   */
  fun release(lease: Lease)
}
