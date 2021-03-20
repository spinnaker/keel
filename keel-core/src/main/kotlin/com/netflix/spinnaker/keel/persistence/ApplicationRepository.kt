package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.graphql.types.Application
import com.netflix.spinnaker.keel.graphql.types.Environment
import com.netflix.spinnaker.keel.graphql.types.Resource

interface ApplicationRepository {
  /**
   * Returns an [Application] if exists. Throws otherwise.
   */
  fun getApplication(appName: String): Application

  /**
  * Returns a list of [Environment]s for a given application
  */
  fun getEnvironments(deliveryConfigId: String): List<Environment>

  /**
   * Returns a list of [Resource]s for a given environment
   */
  fun getResources(environmentId: String): List<Resource>

  /**
   * Returns the status of a given [Resource]
   */
  fun getResourceStatus(resourceId: String): ResourceStatus
}
