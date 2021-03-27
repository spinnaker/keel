package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.graphql.types.Application
import com.netflix.spinnaker.keel.graphql.types.Constraint
import com.netflix.spinnaker.keel.graphql.types.Environment
import com.netflix.spinnaker.keel.graphql.types.Resource

interface ApplicationRepository {
  /**
  * Returns a list of [Environment]s for a given application
  */
  fun getEnvironments(deliveryConfigId: String): List<Environment>

  /**
   * Returns the status of a given [Resource]
   */
  fun getResourceStatus(resourceId: String): ResourceStatus

  /**
   * Returns a list of [Constraint]s for a given environment
   */
  fun getConstraints(environmentId: String): List<Constraint>
}
