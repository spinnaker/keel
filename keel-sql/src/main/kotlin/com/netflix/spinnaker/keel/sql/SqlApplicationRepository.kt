package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.Monikered
import com.netflix.spinnaker.keel.graphql.types.*
import com.netflix.spinnaker.keel.api.Resource as KeelResource
import com.netflix.spinnaker.keel.persistence.ApplicationRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.ResourceStatus
import com.netflix.spinnaker.keel.persistence.metamodel.Tables
import com.netflix.spinnaker.keel.persistence.metamodel.tables.DeliveryConfig.DELIVERY_CONFIG
import com.netflix.spinnaker.keel.persistence.metamodel.tables.Environment.ENVIRONMENT
import com.netflix.spinnaker.keel.persistence.metamodel.tables.EnvironmentResource.ENVIRONMENT_RESOURCE
import com.netflix.spinnaker.keel.persistence.metamodel.tables.ResourceWithMetadata.RESOURCE_WITH_METADATA
import com.netflix.spinnaker.keel.resources.ResourceSpecIdentifier
import com.netflix.spinnaker.keel.resources.SpecMigrator
import com.netflix.spinnaker.keel.services.ResourceStatusService
import org.jooq.DSLContext
import java.time.Clock

class SqlApplicationRepository(
  private val clock: Clock,
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry,
  private val objectMapper: ObjectMapper,
  private val resourceSpecIdentifier: ResourceSpecIdentifier,
  private val specMigrators: List<SpecMigrator<*, *>>,
  private val resourceRepository: ResourceRepository,
  private val resourceStatusService: ResourceStatusService
) : ApplicationRepository {

  override fun getEnvironments(deliveryConfigId: String): List<Environment> {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(
        ENVIRONMENT.UID,
        ENVIRONMENT.NAME,
      ).from(ENVIRONMENT).where(ENVIRONMENT.DELIVERY_CONFIG_UID.eq(deliveryConfigId)).fetch()
        .map { (uid, name) ->
          Environment(
            name = name,
            resources = emptyList(),
            constraints = emptyList(),
            artifacts = emptyList()
          )
        }
    }
  }

  override fun getResourceStatus(resourceId: String): ResourceStatus {
    return resourceStatusService.getStatus(resourceId)
  }

  override fun getConstraints(environmentId: String): List<Constraint> {
    return emptyList()
  }

}
