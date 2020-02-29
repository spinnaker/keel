package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.core.api.randomUID
import com.netflix.spinnaker.keel.events.ApplicationEvent
import com.netflix.spinnaker.keel.events.PersistentEvent.Scope
import com.netflix.spinnaker.keel.events.ResourceEvent
import com.netflix.spinnaker.keel.persistence.NoSuchApplication
import com.netflix.spinnaker.keel.persistence.NoSuchResourceId
import com.netflix.spinnaker.keel.persistence.ResourceHeader
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.ResourceSummary
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DIFF_FINGERPRINT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.EVENT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.RESOURCE
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.RESOURCE_LAST_CHECKED
import com.netflix.spinnaker.keel.resources.ResourceTypeIdentifier
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import de.huxhorn.sulky.ulid.ULID
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.Instant.EPOCH
import org.jooq.DSLContext
import org.jooq.impl.DSL.select

open class SqlResourceRepository(
  private val jooq: DSLContext,
  private val clock: Clock,
  private val resourceTypeIdentifier: ResourceTypeIdentifier,
  private val objectMapper: ObjectMapper,
  private val sqlRetry: SqlRetry
) : ResourceRepository {

  override fun deleteByApplication(application: String): Int {
    val resourceIds = getResourceIdsByApplication(application)
    val resourceUids = getUidByApplication(application)

    resourceUids.sorted().chunked(10).forEach { chunk ->
      sqlRetry.withRetry(WRITE) {
        jooq.deleteFrom(RESOURCE)
          .where(RESOURCE.UID.`in`(*chunk.toTypedArray()))
          .execute()

        jooq.deleteFrom(RESOURCE_LAST_CHECKED)
          .where(RESOURCE_LAST_CHECKED.RESOURCE_UID.`in`(*chunk.toTypedArray()))
          .execute()

        jooq.deleteFrom(EVENT)
          .where(EVENT.SCOPE.eq(Scope.RESOURCE.name))
          .and(EVENT.UID.`in`(*chunk.toTypedArray()))
          .execute()
      }
    }

    resourceIds.sorted().chunked(10).forEach { chunk ->
      sqlRetry.withRetry(WRITE) {
        jooq.deleteFrom(DIFF_FINGERPRINT)
          .where(DIFF_FINGERPRINT.RESOURCE_ID.`in`(*chunk.toTypedArray()))
          .execute()
      }
    }

    return resourceUids.size
  }

  override fun allResources(callback: (ResourceHeader) -> Unit) {
    sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.API_VERSION, RESOURCE.KIND, RESOURCE.ID)
        .from(RESOURCE)
        .fetch()
        .map { (apiVersion, kind, id) ->
          ResourceHeader(id, apiVersion, kind)
        }
        .forEach(callback)
    }
  }

  override fun get(id: String): Resource<out ResourceSpec> {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.API_VERSION, RESOURCE.KIND, RESOURCE.METADATA, RESOURCE.SPEC)
        .from(RESOURCE)
        .where(RESOURCE.ID.eq(id))
        .fetchOne()
        ?.let { (apiVersion, kind, metadata, spec) ->
          constructResource(apiVersion, kind, metadata, spec)
        } ?: throw NoSuchResourceId(id)
    }
  }

  override fun getResourcesByApplication(application: String): List<Resource<*>> {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.API_VERSION, RESOURCE.KIND, RESOURCE.METADATA, RESOURCE.SPEC)
        .from(RESOURCE)
        .where(RESOURCE.APPLICATION.eq(application))
        .fetch()
        .map { (apiVersion, kind, metadata, spec) ->
          constructResource(apiVersion, kind, metadata, spec)
        }
    }
  }

  /**
   * Constructs a resource object from its database representation
   */
  private fun constructResource(apiVersion: String, kind: String, metadata: String, spec: String) =
    Resource(
      apiVersion,
      kind,
      objectMapper.readValue<Map<String, Any?>>(metadata).asResourceMetadata(),
      objectMapper.readValue(spec, resourceTypeIdentifier.identify(apiVersion, kind))
    )

  override fun hasManagedResources(application: String): Boolean {
    return sqlRetry.withRetry(READ) {
      jooq
        .selectCount()
        .from(RESOURCE)
        .where(RESOURCE.APPLICATION.eq(application))
        .fetchOne()
        .value1() > 0
    }
  }

  override fun getResourceIdsByApplication(application: String): List<String> {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.ID)
        .from(RESOURCE)
        .where(RESOURCE.APPLICATION.eq(application))
        .fetch(RESOURCE.ID)
    }
  }

  fun getUidByApplication(application: String): List<String> {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.UID)
        .from(RESOURCE)
        .where(RESOURCE.APPLICATION.eq(application))
        .fetch(RESOURCE.UID)
    }
  }

  override fun getSummaryByApplication(application: String): List<ResourceSummary> {
    val resources: List<Resource<*>> = sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.API_VERSION, RESOURCE.KIND, RESOURCE.METADATA, RESOURCE.SPEC)
        .from(RESOURCE)
        .where(RESOURCE.APPLICATION.eq(application))
        .fetch()
        .map { (apiVersion, kind, metadata, spec) ->
          Resource(
            apiVersion,
            kind,
            objectMapper.readValue<Map<String, Any?>>(metadata).asResourceMetadata(),
            objectMapper.readValue(spec, resourceTypeIdentifier.identify(apiVersion, kind))
          )
        }
    }
    return resources.map { it.toResourceSummary() }
  }

  // todo: this is not retryable due to overall repository structure: https://github.com/spinnaker/keel/issues/740
  override fun store(resource: Resource<*>) {
    val uid = jooq.select(RESOURCE.UID)
      .from(RESOURCE)
      .where(RESOURCE.ID.eq(resource.id))
      .fetchOne(RESOURCE.UID)
      ?: randomUID().toString()

    val updatePairs = mapOf(
      RESOURCE.API_VERSION to resource.apiVersion,
      RESOURCE.KIND to resource.kind,
      RESOURCE.ID to resource.id,
      RESOURCE.METADATA to objectMapper.writeValueAsString(resource.metadata + ("uid" to uid)),
      RESOURCE.SPEC to objectMapper.writeValueAsString(resource.spec),
      RESOURCE.APPLICATION to resource.application
    )
    val insertPairs = updatePairs + (RESOURCE.UID to uid)
    jooq.insertInto(
      RESOURCE,
      *insertPairs.keys.toTypedArray()
    )
      .values(*insertPairs.values.toTypedArray())
      .onDuplicateKeyUpdate()
      .set(updatePairs)
      .execute()
    jooq.insertInto(RESOURCE_LAST_CHECKED)
      .set(RESOURCE_LAST_CHECKED.RESOURCE_UID, uid)
      .set(RESOURCE_LAST_CHECKED.AT, EPOCH.plusSeconds(1).toLocal())
      .onDuplicateKeyUpdate()
      .set(RESOURCE_LAST_CHECKED.AT, EPOCH.plusSeconds(1).toLocal())
      .execute()
  }

  override fun applicationEventHistory(application: String, limit: Int): List<ApplicationEvent> {
    require(limit > 0) { "limit must be a positive integer" }
    return sqlRetry.withRetry(READ) {
      jooq
        .select(EVENT.JSON)
        .from(EVENT)
        .where(EVENT.SCOPE.eq(Scope.APPLICATION.name))
        .and(EVENT.UID.eq(application))
        .orderBy(EVENT.TIMESTAMP.desc())
        .limit(limit)
        .fetch()
        .map { (json) ->
          objectMapper.readValue<ApplicationEvent>(json)
        }
        .ifEmpty {
          throw NoSuchApplication(application)
        }
    }
  }

  override fun eventHistory(id: String, limit: Int): List<ResourceEvent> {
    require(limit > 0) { "limit must be a positive integer" }
    return sqlRetry.withRetry(READ) {
      jooq
        .select(EVENT.JSON)
        .from(EVENT, RESOURCE)
        .where(EVENT.SCOPE.eq(Scope.RESOURCE.name))
        .and(RESOURCE.ID.eq(id))
        .and(RESOURCE.UID.eq(EVENT.UID))
        .orderBy(EVENT.TIMESTAMP.desc())
        .limit(limit)
        .fetch()
        .map { (json) ->
          objectMapper.readValue<ResourceEvent>(json)
        }
        .ifEmpty {
          throw NoSuchResourceId(id)
        }
    }
  }

  override fun appendHistory(event: ApplicationEvent) {
    if (event.ignoreRepeatedInHistory) {
      val previousEvent = jooq
        .select(EVENT.JSON)
        .from(EVENT)
        .where(EVENT.SCOPE.eq(Scope.APPLICATION.name))
        .and(EVENT.UID.eq(event.application))
        .orderBy(EVENT.TIMESTAMP.desc())
        .limit(1)
        .fetchOne()
        ?.let { (json) ->
          objectMapper.readValue<ApplicationEvent>(json)
        }

      if (event.javaClass == previousEvent?.javaClass) return
    }

    jooq
      .insertInto(EVENT)
      .set(EVENT.SCOPE, Scope.APPLICATION.name)
      .set(EVENT.UID, event.application)
      .set(EVENT.TIMESTAMP, event.timestamp.atZone(clock.zone).toLocalDateTime())
      .set(EVENT.JSON, objectMapper.writeValueAsString(event))
      .execute()
  }

  // todo: add sql retries once we've rethought repository structure: https://github.com/spinnaker/keel/issues/740
  override fun appendHistory(event: ResourceEvent) {
    if (event.ignoreRepeatedInHistory) {
      val previousEvent = jooq
        .select(EVENT.JSON)
        .from(EVENT, RESOURCE)
        .where(EVENT.SCOPE.eq(Scope.RESOURCE.name))
        .and(RESOURCE.ID.eq(event.id))
        .and(RESOURCE.UID.eq(EVENT.UID))
        .orderBy(EVENT.TIMESTAMP.desc())
        .limit(1)
        .fetchOne()
        ?.let { (json) ->
          objectMapper.readValue<ResourceEvent>(json)
        }

      if (event.javaClass == previousEvent?.javaClass) return
    }

    jooq
      .insertInto(EVENT)
      .set(EVENT.SCOPE, Scope.RESOURCE.name)
      .set(EVENT.UID, select(RESOURCE.UID).from(RESOURCE).where(RESOURCE.ID.eq(event.id)))
      .set(EVENT.TIMESTAMP, event.timestamp.atZone(clock.zone).toLocalDateTime())
      .set(EVENT.JSON, objectMapper.writeValueAsString(event))
      .execute()
  }

  override fun delete(id: String) {
    val uid = sqlRetry.withRetry(READ) {
      jooq.select(RESOURCE.UID)
        .from(RESOURCE)
        .where(RESOURCE.ID.eq(id))
        .fetchOne(RESOURCE.UID)
        ?.let(ULID::parseULID)
        ?: throw NoSuchResourceId(id)
    }
    sqlRetry.withRetry(WRITE) {
      jooq.deleteFrom(RESOURCE)
        .where(RESOURCE.UID.eq(uid.toString()))
        .execute()
    }
    sqlRetry.withRetry(WRITE) {
      jooq.deleteFrom(EVENT)
        .where(EVENT.SCOPE.eq(Scope.RESOURCE.name))
        .and(EVENT.UID.eq(uid.toString()))
        .execute()
    }
    sqlRetry.withRetry(WRITE) {
      jooq.deleteFrom(DIFF_FINGERPRINT)
        .where(DIFF_FINGERPRINT.RESOURCE_ID.eq(id))
        .execute()
    }
  }

  override fun itemsDueForCheck(minTimeSinceLastCheck: Duration, limit: Int): Collection<Resource<out ResourceSpec>> {
    val now = clock.instant()
    val cutoff = now.minus(minTimeSinceLastCheck).toLocal()
    return sqlRetry.withRetry(WRITE) {
      jooq.inTransaction {
        select(RESOURCE.UID, RESOURCE.API_VERSION, RESOURCE.KIND, RESOURCE.METADATA, RESOURCE.SPEC)
          .from(RESOURCE, RESOURCE_LAST_CHECKED)
          .where(RESOURCE.UID.eq(RESOURCE_LAST_CHECKED.RESOURCE_UID))
          .and(RESOURCE_LAST_CHECKED.AT.lessOrEqual(cutoff))
          .orderBy(RESOURCE_LAST_CHECKED.AT)
          .limit(limit)
          .forUpdate()
          .fetch()
          .also {
            it.forEach { (uid, _, _, _, _) ->
              insertInto(RESOURCE_LAST_CHECKED)
                .set(RESOURCE_LAST_CHECKED.RESOURCE_UID, uid)
                .set(RESOURCE_LAST_CHECKED.AT, now.toLocal())
                .onDuplicateKeyUpdate()
                .set(RESOURCE_LAST_CHECKED.AT, now.toLocal())
                .execute()
            }
          }
          .map { (_, apiVersion, kind, metadata, spec) ->
            constructResource(apiVersion, kind, metadata, spec)
          }
      }
    }
  }

  private fun Instant.toLocal() = atZone(clock.zone).toLocalDateTime()
}
