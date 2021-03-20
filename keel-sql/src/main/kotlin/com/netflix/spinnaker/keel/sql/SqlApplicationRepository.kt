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

  private val resourceFactory = ResourceFactory(objectMapper, resourceSpecIdentifier, specMigrators)


  override fun getApplication(appName: String): Application {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(
        DELIVERY_CONFIG.UID,
        DELIVERY_CONFIG.APPLICATION,
        DELIVERY_CONFIG.SERVICE_ACCOUNT
      ).from(DELIVERY_CONFIG).where(DELIVERY_CONFIG.APPLICATION.eq(appName)).fetchOne()
    }
      .let { (uid, name, account) ->
        Application(
          uid = uid,
          name = name,
          account = account,
          environments = emptyList()
        )
      }
    // TODO: handle not found
  }

  override fun getEnvironments(deliveryConfigId: String): List<Environment> {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(
        ENVIRONMENT.UID,
        ENVIRONMENT.NAME,
      ).from(ENVIRONMENT).where(ENVIRONMENT.DELIVERY_CONFIG_UID.eq(deliveryConfigId)).fetch()
        .map { (uid, name) -> Environment(uid = uid, name = name) }
    }
  }

  override fun getResources(environmentId: String): List<Resource> {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(
        RESOURCE_WITH_METADATA.UID,
        RESOURCE_WITH_METADATA.ID,
        RESOURCE_WITH_METADATA.KIND,
        RESOURCE_WITH_METADATA.METADATA,
        RESOURCE_WITH_METADATA.SPEC
      )
        .from(RESOURCE_WITH_METADATA, Tables.ENVIRONMENT_RESOURCE)
        .where(RESOURCE_WITH_METADATA.UID.eq(Tables.ENVIRONMENT_RESOURCE.RESOURCE_UID))
        .and(ENVIRONMENT_RESOURCE.ENVIRONMENT_UID.eq(environmentId))
        .fetch()
        .map { (uid, ref, kind, metadata, spec) ->
          val result = resourceFactory.invoke(kind, metadata, spec)
          val moniker = if (result.spec is Monikered) {
            (result.spec as Monikered).moniker.let {
              Moniker(app = it.app, stack = it.stack, detail = it.detail)
            }
          } else {
            null
          }
          val location = if (result.spec is Locatable<*>) {
            Location(regions = (result.spec as Locatable<*>).locations.regions.map { it.name })
          } else {
            null
          }
          Resource(uid = uid, ref = ref, kind = result.kind.toString(), moniker = moniker, location = location)
        }
    }
  }

  override fun getResourceStatus(resourceId: String): ResourceStatus {
    return resourceStatusService.getStatus(resourceId)
  }

}
