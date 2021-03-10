package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.persistence.EnvironmentLeaseRepository
import com.netflix.spinnaker.keel.persistence.Lease

/**
 * This code would be implemented by making calls against a new table: environment_lease
 *
 */
class SqlEnvironmentLeaseRepository : EnvironmentLeaseRepository {
  override fun tryAcquireLease(environment: Environment): Lease {
    TODO("Not yet implemented")
  }

  override fun release(lease: Lease) {
    TODO("Not yet implemented")
  }
}
