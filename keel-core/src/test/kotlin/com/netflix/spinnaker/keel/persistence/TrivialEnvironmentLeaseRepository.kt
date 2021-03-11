package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.Environment

/**
 * A trivial (no-op) implementation, used to simplify testing
 */
class TrivialEnvironmentLeaseRepository : EnvironmentLeaseRepository {
  class TrivialLease : Lease {}

  override fun tryAcquireLease(environment: Environment): Lease {
    return TrivialLease()
  }

  override fun release(lease: Lease) {
    // nothing to do
  }
}
