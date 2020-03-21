package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.ComputeResourceSpec
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceKind.Companion.parseKind
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.core.api.ResourceSummary
import com.netflix.spinnaker.keel.core.api.randomUID
import com.netflix.spinnaker.keel.events.ApplicationEvent
import com.netflix.spinnaker.keel.events.PersistentEvent.Scope
import com.netflix.spinnaker.keel.events.ResourceEvent
import com.netflix.spinnaker.keel.persistence.NoSuchResourceId
import com.netflix.spinnaker.keel.persistence.ResourceHeader
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DELIVERY_ARTIFACT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DIFF_FINGERPRINT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.EVENT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.RESOURCE
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.RESOURCE_ARTIFACT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.RESOURCE_LAST_CHECKED
import com.netflix.spinnaker.keel.resources.ResourceTypeIdentifier
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import de.huxhorn.sulky.ulid.ULID
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.Instant.EPOCH
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.DSL.select

open class SqlResourceRepository(
  private val jooq: DSLContext,
  private val clock: Clock,
  private val resourceTypeIdentifier: ResourceTypeIdentifier,
  private val objectMapper: ObjectMapper,
  private val sqlRetry: SqlRetry
) : ResourceRepository {

  override fun allResources(callback: (ResourceHeader) -> Unit) {
    sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.KIND, RESOURCE.ID)
        .from(RESOURCE)
        .fetch()
        .map { (kind, id) ->
          ResourceHeader(id, parseKind(kind))
        }
        .forEach(callback)
    }
  }

  override fun get(id: String): Resource<out ResourceSpec> {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.KIND, RESOURCE.METADATA, RESOURCE.SPEC)
        .from(RESOURCE)
        .where(RESOURCE.ID.eq(id))
        .fetchOne()
        ?.let { (kind, metadata, spec) ->
          constructResource(kind, metadata, spec)
        } ?: throw NoSuchResourceId(id)
    }
  }

  override fun getResourcesByApplication(application: String): List<Resource<*>> {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.KIND, RESOURCE.METADATA, RESOURCE.SPEC)
        .from(RESOURCE)
        .where(RESOURCE.APPLICATION.eq(application))
        .fetch()
        .map { (kind, metadata, spec) ->
          constructResource(kind, metadata, spec)
        }
    }
  }

  /**
   * Constructs a resource object from its database representation
   */
  private fun constructResource(kind: String, metadata: String, spec: String) =
    Resource(
      parseKind(kind),
      objectMapper.readValue<Map<String, Any?>>(metadata).asResourceMetadata(),
      objectMapper.readValue(spec, resourceTypeIdentifier.identify(parseKind(kind)))
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

  override fun getSummaryByApplication(application: String): List<ResourceSummary> {
    val resources: List<Resource<*>> = sqlRetry.withRetry(READ) {
      jooq
        .select(RESOURCE.KIND, RESOURCE.METADATA, RESOURCE.SPEC, DELIVERY_ARTIFACT.NAME, DELIVERY_ARTIFACT.TYPE)
        .from(RESOURCE)
        .leftOuterJoin(RESOURCE_ARTIFACT)
        .on(RESOURCE_ARTIFACT.RESOURCE_UID.eq(RESOURCE.UID))
        .leftOuterJoin(DELIVERY_ARTIFACT)
        .on(DELIVERY_ARTIFACT.UID.eq(RESOURCE_ARTIFACT.ARTIFACT_UID))
        .where(RESOURCE.APPLICATION.eq(application))
        .fetch()
        .map { (kind, metadata, spec, artifactName, artifactType) ->
          val specMap = objectMapper.readValue<MutableMap<String, Any?>>(spec)
          // if the resource is associated with an artifact, add the artifact info to the spec
          if (artifactName != null && artifactType != null) {
            specMap["artifactName"] = artifactName
            specMap["artifactType"] = ArtifactType.valueOf(artifactType)
          }
          Resource(
            kind = parseKind(kind),
            metadata = objectMapper.readValue<Map<String, Any?>>(metadata).asResourceMetadata(),
            spec = objectMapper.convertValue(specMap, resourceTypeIdentifier.identify(parseKind(kind)))
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
    }
  }

  override fun applicationEventHistory(application: String, until: Instant): List<ApplicationEvent> {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(EVENT.JSON)
        .from(EVENT)
        .where(EVENT.SCOPE.eq(Scope.APPLICATION.name))
        .and(EVENT.UID.eq(application))
        .and(EVENT.TIMESTAMP.lessOrEqual(LocalDateTime.ofInstant(until, ZoneOffset.UTC)))
        .orderBy(EVENT.TIMESTAMP.desc())
        .fetch()
        .map { (json) ->
          objectMapper.readValue<ApplicationEvent>(json)
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
    jooq.transaction { config ->
      val txn = DSL.using(config)

      if (event.ignoreRepeatedInHistory) {
        val previousEvent = txn
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

        if (event.javaClass == previousEvent?.javaClass) return@transaction
      }

      txn
        .insertInto(EVENT)
        .set(EVENT.SCOPE, Scope.APPLICATION.name)
        .set(EVENT.UID, event.application)
        .set(EVENT.TIMESTAMP, event.timestamp.atZone(clock.zone).toLocalDateTime())
        .set(EVENT.JSON, objectMapper.writeValueAsString(event))
        .execute()
    }
  }

  // todo: add sql retries once we've rethought repository structure: https://github.com/spinnaker/keel/issues/740
  override fun appendHistory(event: ResourceEvent) {
    jooq.transaction { config ->
      val txn = DSL.using(config)

      if (event.ignoreRepeatedInHistory) {
        val previousEvent = txn
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

        if (event.javaClass == previousEvent?.javaClass) return@transaction
      }

      txn
        .insertInto(EVENT)
        .set(EVENT.SCOPE, Scope.RESOURCE.name)
        .set(EVENT.UID, select(RESOURCE.UID).from(RESOURCE).where(RESOURCE.ID.eq(event.id)))
        .set(EVENT.TIMESTAMP, event.timestamp.atZone(clock.zone).toLocalDateTime())
        .set(EVENT.JSON, objectMapper.writeValueAsString(event))
        .execute()
    }
  }

  override fun delete(id: String) {
    jooq.transaction { config ->
      val txn = DSL.using(config)

      val uid = sqlRetry.withRetry(READ) {
        txn.select(RESOURCE.UID)
          .from(RESOURCE)
          .where(RESOURCE.ID.eq(id))
          .fetchOne(RESOURCE.UID)
          ?.let(ULID::parseULID)
          ?: throw NoSuchResourceId(id)
      }
      sqlRetry.withRetry(WRITE) {
        txn.deleteFrom(RESOURCE)
          .where(RESOURCE.UID.eq(uid.toString()))
          .execute()
      }
      sqlRetry.withRetry(WRITE) {
        txn.deleteFrom(EVENT)
          .where(EVENT.SCOPE.eq(Scope.RESOURCE.name))
          .and(EVENT.UID.eq(uid.toString()))
          .execute()
      }
      sqlRetry.withRetry(WRITE) {
        txn.deleteFrom(DIFF_FINGERPRINT)
          .where(DIFF_FINGERPRINT.RESOURCE_ID.eq(id))
          .execute()
      }
      sqlRetry.withRetry(WRITE) {
        txn.deleteFrom(RESOURCE_ARTIFACT)
          .where(RESOURCE_ARTIFACT.RESOURCE_UID.eq(uid.toString()))
          .execute()
      }
    }
  }

  override fun itemsDueForCheck(minTimeSinceLastCheck: Duration, limit: Int): Collection<Resource<out ResourceSpec>> {
    val now = clock.instant()
    val cutoff = now.minus(minTimeSinceLastCheck).toLocal()
    return sqlRetry.withRetry(WRITE) {
      jooq.inTransaction {
        select(RESOURCE.UID, RESOURCE.KIND, RESOURCE.METADATA, RESOURCE.SPEC)
          .from(RESOURCE, RESOURCE_LAST_CHECKED)
          .where(RESOURCE.UID.eq(RESOURCE_LAST_CHECKED.RESOURCE_UID))
          .and(RESOURCE_LAST_CHECKED.AT.lessOrEqual(cutoff))
          .orderBy(RESOURCE_LAST_CHECKED.AT)
          .limit(limit)
          .forUpdate()
          .fetch()
          .also {
            it.forEach { (uid, _, _, _) ->
              insertInto(RESOURCE_LAST_CHECKED)
                .set(RESOURCE_LAST_CHECKED.RESOURCE_UID, uid)
                .set(RESOURCE_LAST_CHECKED.AT, now.toLocal())
                .onDuplicateKeyUpdate()
                .set(RESOURCE_LAST_CHECKED.AT, now.toLocal())
                .execute()
            }
          }
          .map { (_, kind, metadata, spec) ->
            constructResource(kind, metadata, spec)
          }
      }
    }
  }

  override fun <S : ComputeResourceSpec> associate(resource: Resource<S>, artifact: DeliveryArtifact) {
    sqlRetry.withRetry(WRITE) {
      jooq.insertInto(RESOURCE_ARTIFACT)
        .set(RESOURCE_ARTIFACT.RESOURCE_UID, resource.uid)
        .set(RESOURCE_ARTIFACT.ARTIFACT_UID, artifact.uid)
        .execute()
    }
  }

  override fun <S : ComputeResourceSpec> getArtifactForResource(resource: Resource<S>): DeliveryArtifact? {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(
          DELIVERY_ARTIFACT.NAME,
          DELIVERY_ARTIFACT.TYPE,
          DELIVERY_ARTIFACT.DETAILS,
          DELIVERY_ARTIFACT.REFERENCE,
          DELIVERY_ARTIFACT.DELIVERY_CONFIG_NAME
        )
        .from(RESOURCE, DELIVERY_ARTIFACT, RESOURCE_ARTIFACT)
        .where(RESOURCE.ID.eq(resource.id))
        .and(RESOURCE_ARTIFACT.RESOURCE_UID.eq(RESOURCE.UID))
        .and(RESOURCE_ARTIFACT.ARTIFACT_UID.eq(DELIVERY_ARTIFACT.UID))
        .and(DELIVERY_ARTIFACT.UID.eq(RESOURCE_ARTIFACT.ARTIFACT_UID))
        .limit(1)
        .fetchOne { (name, type, details, reference, deliveryConfigName) ->
          mapToArtifact(name, ArtifactType.valueOf(type), details, reference, deliveryConfigName)
        }
    }
  }

  private fun Instant.toLocal() = atZone(clock.zone).toLocalDateTime()

  private val Resource<*>.uid: String
    get() = jooq.select(RESOURCE.UID)
      .from(RESOURCE)
      .where(RESOURCE.ID.eq(id))
      .limit(1)
      .fetchOne(RESOURCE.UID)

  private val DeliveryArtifact.uid: String
    get() = jooq.select(DELIVERY_ARTIFACT.UID)
      .from(DELIVERY_ARTIFACT)
      .where(DELIVERY_ARTIFACT.NAME.eq(name))
      .and(DELIVERY_ARTIFACT.TYPE.eq(type.name))
      .and(DELIVERY_ARTIFACT.REFERENCE.eq(reference))
      .limit(1)
      .fetchOne(DELIVERY_ARTIFACT.UID)
}
