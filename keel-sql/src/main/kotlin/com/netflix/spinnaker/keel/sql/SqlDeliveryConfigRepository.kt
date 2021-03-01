package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.constraints.ConstraintState
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.api.constraints.allPass
import com.netflix.spinnaker.keel.api.plugins.ArtifactSupplier
import com.netflix.spinnaker.keel.api.statefulCount
import com.netflix.spinnaker.keel.core.api.ApplicationSummary
import com.netflix.spinnaker.keel.core.api.UID
import com.netflix.spinnaker.keel.core.api.parseUID
import com.netflix.spinnaker.keel.core.api.randomUID
import com.netflix.spinnaker.keel.core.api.timestampAsInstant
import com.netflix.spinnaker.keel.events.PersistentEvent.EventScope
import com.netflix.spinnaker.keel.pause.PauseScope
import com.netflix.spinnaker.keel.pause.PauseScope.APPLICATION
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.NoDeliveryConfigForApplication
import com.netflix.spinnaker.keel.persistence.NoSuchDeliveryConfigName
import com.netflix.spinnaker.keel.persistence.OrphanedResourceException
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ARTIFACT_VERSIONS
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.CURRENT_CONSTRAINT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DELIVERY_ARTIFACT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DELIVERY_CONFIG
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DELIVERY_CONFIG_ARTIFACT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DELIVERY_CONFIG_LAST_CHECKED
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT_ARTIFACT_CONSTRAINT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT_RESOURCE
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.EVENT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.PAUSED
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.RESOURCE
import com.netflix.spinnaker.keel.resources.ResourceSpecIdentifier
import com.netflix.spinnaker.keel.resources.SpecMigrator
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import com.netflix.spinnaker.keel.sql.deliveryconfigs.attachDependents
import com.netflix.spinnaker.keel.sql.deliveryconfigs.deliveryConfigByName
import com.netflix.spinnaker.keel.sql.deliveryconfigs.resourcesForEnvironment
import de.huxhorn.sulky.ulid.ULID
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.Select
import org.jooq.impl.DSL
import org.jooq.impl.DSL.count
import org.jooq.impl.DSL.inline
import org.jooq.impl.DSL.max
import org.jooq.impl.DSL.select
import org.jooq.util.mysql.MySQLDSL.values
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.Instant.EPOCH

class SqlDeliveryConfigRepository(
  jooq: DSLContext,
  clock: Clock,
  resourceSpecIdentifier: ResourceSpecIdentifier,
  objectMapper: ObjectMapper,
  sqlRetry: SqlRetry,
  artifactSuppliers: List<ArtifactSupplier<*, *>> = emptyList(),
  specMigrators: List<SpecMigrator<*, *>> = emptyList()
) : SqlStorageContext(
  jooq,
  clock,
  sqlRetry,
  objectMapper,
  resourceSpecIdentifier,
  artifactSuppliers,
  specMigrators
), DeliveryConfigRepository {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  private val RECHECK_LEASE_NAME = "recheck"

  override fun getByApplication(application: String): DeliveryConfig =
    sqlRetry.withRetry(READ) {
      jooq
        .select(
          DELIVERY_CONFIG.UID,
          DELIVERY_CONFIG.NAME,
          DELIVERY_CONFIG.APPLICATION,
          DELIVERY_CONFIG.SERVICE_ACCOUNT,
          DELIVERY_CONFIG.METADATA
        )
        .from(DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG.APPLICATION.eq(application))
        .fetchOne { (uid, name, application, serviceAccount, metadata) ->
          DeliveryConfig(
            name = name,
            application = application,
            serviceAccount = serviceAccount,
            metadata = (metadata ?: emptyMap()) + mapOf("createdAt" to ULID.parseULID(uid).timestampAsInstant())
          ).let {
            attachDependents(it)
          }
        }
    } ?: throw NoDeliveryConfigForApplication(application)

  override fun deleteResourceFromEnv(
    deliveryConfigName: String,
    environmentName: String,
    resourceId: String
  ) {
    sqlRetry.withRetry(WRITE) {
      jooq.deleteFrom(ENVIRONMENT_RESOURCE)
        .where(ENVIRONMENT_RESOURCE.ENVIRONMENT_UID.eq(envUid(deliveryConfigName, environmentName)))
        .and(ENVIRONMENT_RESOURCE.RESOURCE_UID.`in`(resourceId.uids))
        .execute()
    }
  }

  override fun deleteEnvironment(deliveryConfigName: String, environmentName: String) {
    val deliveryConfigUid = sqlRetry.withRetry(READ) {
      jooq.select(DELIVERY_CONFIG.UID)
        .from(DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG.NAME.eq(deliveryConfigName))
        .fetchOne(DELIVERY_CONFIG.UID)
    }

    envUidString(deliveryConfigName, environmentName)
      ?.let { envUid ->
        sqlRetry.withRetry(WRITE) {
          jooq.transaction { config ->
            val txn = DSL.using(config)
            txn
              .deleteFrom(ENVIRONMENT)
              .where(ENVIRONMENT.UID.eq(envUid))
              .and(ENVIRONMENT.DELIVERY_CONFIG_UID.eq(deliveryConfigUid))
              .execute()
          }
        }
      }
  }

  override fun deleteByName(name: String) {
    val application = sqlRetry.withRetry(READ) {
      jooq
        .select(DELIVERY_CONFIG.APPLICATION)
        .from(DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG.NAME.eq(name))
        .fetchOne(DELIVERY_CONFIG.APPLICATION)
    } ?: throw NoSuchDeliveryConfigName(name)
    deleteByApplication(application)
  }

  override fun deleteByApplication(application: String) {
    val configUid = getUIDByApplication(application)
    val resourceUids = getResourceUIDs(application)
    val resourceIds = getResourceIDs(application)
    val artifactUids = getArtifactUIDs(configUid)

    sqlRetry.withRetry(WRITE) {
      jooq.transaction { config ->
        val txn = DSL.using(config)
        // delete events
        txn.deleteFrom(EVENT)
          .where(EVENT.SCOPE.eq(EventScope.RESOURCE))
          .and(EVENT.REF.`in`(resourceUids))
          .execute()
        txn.deleteFrom(EVENT)
          .where(EVENT.SCOPE.eq(EventScope.APPLICATION))
          .and(EVENT.REF.eq(application))
          .execute()
        // delete pause records
        txn.deleteFrom(PAUSED)
          .where(PAUSED.SCOPE.eq(PauseScope.RESOURCE))
          .and(PAUSED.NAME.`in`(resourceIds))
          .execute()
        txn.deleteFrom(PAUSED)
          .where(PAUSED.SCOPE.eq(PauseScope.APPLICATION))
          .and(PAUSED.NAME.eq(application))
          .execute()
        // delete resources
        txn.deleteFrom(RESOURCE)
          .where(RESOURCE.UID.`in`(resourceUids))
          .execute()
        // delete artifact
        txn.deleteFrom(DELIVERY_ARTIFACT)
          .where(DELIVERY_ARTIFACT.UID.`in`(artifactUids))
          .execute()
        // delete delivery config
        txn.deleteFrom(DELIVERY_CONFIG)
          .where(DELIVERY_CONFIG.UID.eq(configUid))
          .execute()
      }
    }
  }

  private fun getUIDByApplication(application: String): String =
    sqlRetry.withRetry(READ) {
      jooq
        .select(DELIVERY_CONFIG.UID)
        .from(DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG.APPLICATION.eq(application))
        .fetchOne(DELIVERY_CONFIG.UID)
    } ?: throw NoDeliveryConfigForApplication(application)

  /**
   * This deliberately returns _all_ resource UIDs not just the latest versions as it is used when
   * deleting the resources associated with a delivery config.
   */
  private fun getResourceUIDs(application: String): Select<Record1<String>> =
    jooq
      .select(RESOURCE.UID)
      .from(RESOURCE)
      .where(RESOURCE.APPLICATION.eq(application))

  private fun getResourceIDs(application: String): Select<Record1<String>> =
    jooq
      .selectDistinct(RESOURCE.ID)
      .from(RESOURCE)
      .where(RESOURCE.APPLICATION.eq(application))

  private fun getArtifactUIDs(deliveryConfigUid: String): Select<Record1<String>> =
    jooq
      .select(DELIVERY_CONFIG_ARTIFACT.ARTIFACT_UID)
      .from(DELIVERY_CONFIG_ARTIFACT)
      .where(DELIVERY_CONFIG_ARTIFACT.DELIVERY_CONFIG_UID.eq(deliveryConfigUid))

  // todo: queries in this function aren't inherently retryable because of the cross-repository interactions
  // from where this is called: https://github.com/spinnaker/keel/issues/740
  override fun store(deliveryConfig: DeliveryConfig) {
    with(deliveryConfig) {
      val uid = jooq
        .select(DELIVERY_CONFIG.UID)
        .from(DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG.NAME.eq(name))
        .fetchOne(DELIVERY_CONFIG.UID)
        ?: randomUID().toString()
      jooq.insertInto(DELIVERY_CONFIG)
        .set(DELIVERY_CONFIG.UID, uid)
        .set(DELIVERY_CONFIG.NAME, name)
        .set(DELIVERY_CONFIG.APPLICATION, application)
        .set(DELIVERY_CONFIG.SERVICE_ACCOUNT, serviceAccount)
        .set(DELIVERY_CONFIG.METADATA, metadata)
        .onDuplicateKeyUpdate()
        .set(DELIVERY_CONFIG.SERVICE_ACCOUNT, serviceAccount)
        .set(DELIVERY_CONFIG.METADATA, metadata)
        .execute()
      artifacts.forEach { artifact ->
        jooq.insertInto(DELIVERY_CONFIG_ARTIFACT)
          .set(DELIVERY_CONFIG_ARTIFACT.DELIVERY_CONFIG_UID, uid)
          .set(
            DELIVERY_CONFIG_ARTIFACT.ARTIFACT_UID,
            jooq
              .select(DELIVERY_ARTIFACT.UID)
              .from(DELIVERY_ARTIFACT)
              .where(DELIVERY_ARTIFACT.NAME.eq(artifact.name))
              .and(DELIVERY_ARTIFACT.TYPE.eq(artifact.type))
              .and(DELIVERY_ARTIFACT.DELIVERY_CONFIG_NAME.eq(artifact.deliveryConfigName))
              .and(DELIVERY_ARTIFACT.REFERENCE.eq(artifact.reference))
          )
          .onDuplicateKeyIgnore()
          .execute()
      }
      environments.forEach { environment ->
        val environmentUID = (
          jooq
            .select(ENVIRONMENT.UID)
            .from(ENVIRONMENT)
            .where(ENVIRONMENT.DELIVERY_CONFIG_UID.eq(uid))
            .and(ENVIRONMENT.NAME.eq(environment.name))
            .fetchOne(ENVIRONMENT.UID)
            ?: randomUID().toString()
          )
          .also {
            jooq.insertInto(ENVIRONMENT)
              .set(ENVIRONMENT.UID, it)
              .set(ENVIRONMENT.DELIVERY_CONFIG_UID, uid)
              .set(ENVIRONMENT.NAME, environment.name)
              .set(
                ENVIRONMENT.CONSTRAINTS,
                objectMapper.writeValueAsString(environment.constraints)
              )
              .set(
                ENVIRONMENT.NOTIFICATIONS,
                objectMapper.writeValueAsString(environment.notifications)
              )
              .set(
                ENVIRONMENT.VERIFICATIONS,
                objectMapper.writeValueAsString(environment.verifyWith)
              )
              .onDuplicateKeyUpdate()
              .set(
                ENVIRONMENT.CONSTRAINTS,
                objectMapper.writeValueAsString(environment.constraints)
              )
              .set(
                ENVIRONMENT.NOTIFICATIONS,
                objectMapper.writeValueAsString(environment.notifications)
              )
              .set(
                ENVIRONMENT.VERIFICATIONS,
                objectMapper.writeValueAsString(environment.verifyWith)
              )
              .execute()
          }
        environment.resources.forEach { resource ->
          jooq.insertInto(ENVIRONMENT_RESOURCE)
            .set(ENVIRONMENT_RESOURCE.ENVIRONMENT_UID, environmentUID)
            .set(
              ENVIRONMENT_RESOURCE.RESOURCE_UID,
              select(RESOURCE.UID)
                .from(RESOURCE)
                .innerJoin(
                  select(RESOURCE.ID, max(RESOURCE.VERSION).`as`("MAX_VERSION"))
                    .from(RESOURCE)
                    .where(RESOURCE.ID.eq(resource.id))
                    .groupBy(RESOURCE.ID)
                    .asTable("GROUPED_RESOURCE")
                )
                .on(RESOURCE.ID.eq(field("GROUPED_RESOURCE.ID")))
                .and(RESOURCE.VERSION.eq(field("GROUPED_RESOURCE.MAX_VERSION")))
            )
            .onDuplicateKeyIgnore()
            .execute()
          // delete any other environment's link to any version of this same resource (in case we
          // are moving it from one environment to another)
          jooq.deleteFrom(ENVIRONMENT_RESOURCE)
            .where(ENVIRONMENT_RESOURCE.ENVIRONMENT_UID.notEqual(environmentUID))
            .and(
              ENVIRONMENT_RESOURCE.RESOURCE_UID.`in`(
                select(RESOURCE.UID)
                  .from(RESOURCE)
                  .where(RESOURCE.ID.eq(resource.id))
              )
            )
            .execute()
        }
      }
      jooq.insertInto(DELIVERY_CONFIG_LAST_CHECKED)
        .set(DELIVERY_CONFIG_LAST_CHECKED.DELIVERY_CONFIG_UID, uid)
        .set(DELIVERY_CONFIG_LAST_CHECKED.AT, EPOCH.plusSeconds(1))
        .onDuplicateKeyUpdate()
        .set(DELIVERY_CONFIG_LAST_CHECKED.AT, EPOCH.plusSeconds(1))
        .execute()
    }
  }

  override fun get(name: String): DeliveryConfig =
    deliveryConfigByName(name)

  override fun environmentFor(resourceId: String): Environment =
    sqlRetry.withRetry(READ) {
      jooq
        .select(
          ENVIRONMENT.UID,
          ENVIRONMENT.NAME,
          ENVIRONMENT.CONSTRAINTS,
          ENVIRONMENT.NOTIFICATIONS,
          ENVIRONMENT.VERIFICATIONS
        )
        .from(ENVIRONMENT, ENVIRONMENT_RESOURCE, RESOURCE)
        .where(RESOURCE.ID.eq(resourceId))
        .and(ENVIRONMENT_RESOURCE.RESOURCE_UID.eq(RESOURCE.UID))
        .and(ENVIRONMENT_RESOURCE.ENVIRONMENT_UID.eq(ENVIRONMENT.UID))
        .fetchOne { (uid, name, constraintsJson, notificationsJson, verifyWithJson) ->
          Environment(
            name = name,
            resources = resourcesForEnvironment(uid),
            constraints = objectMapper.readValue(constraintsJson),
            notifications = notificationsJson?.let { objectMapper.readValue(it) } ?: emptySet(),
            verifyWith = verifyWithJson?.let { objectMapper.readValue(it) } ?: emptyList()
          )
        }
    } ?: throw OrphanedResourceException(resourceId)

  override fun environmentNotifications(deliveryConfigName: String, environmentName: String): Set<NotificationConfig> =
    sqlRetry.withRetry(READ) {
      val uid = jooq
        .select(DELIVERY_CONFIG.UID)
        .from(DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG.NAME.eq(deliveryConfigName))
        .fetchOne(DELIVERY_CONFIG.UID)
        ?: randomUID().toString()
      jooq
        .select(
          ENVIRONMENT.NOTIFICATIONS,
        )
        .from(ENVIRONMENT)
        .where(ENVIRONMENT.NAME.eq(environmentName))
        .and(ENVIRONMENT.DELIVERY_CONFIG_UID.eq(uid))
        .fetchOne { (notificationsJson) ->
             notificationsJson?.let { objectMapper.readValue(it) }
        } ?: emptySet()
    }

  override fun deliveryConfigFor(resourceId: String): DeliveryConfig =
    // TODO: this implementation could be more efficient by sharing code with get(name)
    sqlRetry.withRetry(READ) {
      jooq
        .select(DELIVERY_CONFIG.NAME)
        .from(ENVIRONMENT, ENVIRONMENT_RESOURCE, RESOURCE, DELIVERY_CONFIG)
        .where(RESOURCE.ID.eq(resourceId))
        .and(ENVIRONMENT_RESOURCE.RESOURCE_UID.eq(RESOURCE.UID))
        .and(ENVIRONMENT_RESOURCE.ENVIRONMENT_UID.eq(ENVIRONMENT.UID))
        .and(ENVIRONMENT.DELIVERY_CONFIG_UID.eq(DELIVERY_CONFIG.UID))
        .fetchOne { (name) ->
          get(name)
        }
    } ?: throw OrphanedResourceException(resourceId)

  /**
   * gets or creates constraint id
   * saves constraint
   * calls [constraintStateForWithTransaction]
   * if all constraints pass, puts in queue for approval table
   */
  override fun storeConstraintState(state: ConstraintState) {
    environmentUidByName(state.deliveryConfigName, state.environmentName)
      ?.also { envUid ->
        val uid = sqlRetry.withRetry(READ) {
          jooq
            .select(ENVIRONMENT_ARTIFACT_CONSTRAINT.UID)
            .from(ENVIRONMENT_ARTIFACT_CONSTRAINT)
            .where(
              ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID.eq(envUid),
              ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE.eq(state.type),
              ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION.eq(state.artifactVersion)
            )
            .fetchOne(ENVIRONMENT_ARTIFACT_CONSTRAINT.UID)
        } ?: randomUID().toString()

        val application = applicationByDeliveryConfigName(state.deliveryConfigName)
        val environment = get(state.deliveryConfigName)
          .environments
          .firstOrNull {
            it.name == state.environmentName
          }
          ?: error("Environment ${state.environmentName} does not exist in ${state.deliveryConfigName}")

        sqlRetry.withRetry(WRITE) {
          jooq.transaction { config ->
            val txn = DSL.using(config)
            txn
              .insertInto(ENVIRONMENT_ARTIFACT_CONSTRAINT)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.UID, uid)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID, envUid)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION, state.artifactVersion)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE, state.type)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.CREATED_AT, state.createdAt)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS, state.status)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_BY, state.judgedBy)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_AT, state.judgedAt)
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.COMMENT, state.comment)
              .set(
                ENVIRONMENT_ARTIFACT_CONSTRAINT.ATTRIBUTES,
                objectMapper.writeValueAsString(state.attributes)
              )
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE, state.artifactReference)
              .onDuplicateKeyUpdate()
              .set(
                ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS,
                values(ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS)
              )
              .set(
                ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_BY,
                values(ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_BY)
              )
              .set(
                ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_AT,
                values(ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_AT)
              )
              .set(
                ENVIRONMENT_ARTIFACT_CONSTRAINT.COMMENT,
                values(ENVIRONMENT_ARTIFACT_CONSTRAINT.COMMENT)
              )
              .set(
                ENVIRONMENT_ARTIFACT_CONSTRAINT.ATTRIBUTES,
                values(ENVIRONMENT_ARTIFACT_CONSTRAINT.ATTRIBUTES)
              )
              .set(ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE, state.artifactReference)
              .execute()

            txn
              .insertInto(CURRENT_CONSTRAINT)
              .set(CURRENT_CONSTRAINT.APPLICATION, application)
              .set(CURRENT_CONSTRAINT.ENVIRONMENT_UID, envUid)
              .set(CURRENT_CONSTRAINT.TYPE, state.type)
              .set(CURRENT_CONSTRAINT.CONSTRAINT_UID, uid)
              .onDuplicateKeyUpdate()
              .set(
                CURRENT_CONSTRAINT.CONSTRAINT_UID,
                values(CURRENT_CONSTRAINT.CONSTRAINT_UID)
              )
              .execute()

            /**
             * Passing the transaction here since [constraintStateForWithTransaction] is querying [ENVIRONMENT_ARTIFACT_CONSTRAINT]
             * table, and we need to make sure the new state was persisted prior to checking all states for a given artifact version.
             */
            val allStates = constraintStateForWithTransaction(
              state.deliveryConfigName,
              state.environmentName,
              state.artifactVersion,
              txn
            )
            if (allStates.allPass && allStates.size >= environment.constraints.statefulCount) {
              txn.insertInto(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL)
                .set(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ENVIRONMENT_UID, envUid)
                .set(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_VERSION, state.artifactVersion)
                .set(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.QUEUED_AT, clock.instant())
                .set(
                  ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_REFERENCE,
                  state.artifactReference
                )
                .onDuplicateKeyIgnore()
                .execute()
            }
          }
          // Store generated UID in constraint state object so it can be used by caller
          state.uid = parseUID(uid)
        }
      }
  }

  override fun getConstraintState(
    deliveryConfigName: String,
    environmentName: String,
    artifactVersion: String,
    type: String,
    artifactReference: String?
  ): ConstraintState? {
    val environmentUID = environmentUidByName(deliveryConfigName, environmentName)
      ?: return null
    return sqlRetry.withRetry(READ) {
      jooq
        .select(
          inline(deliveryConfigName).`as`("deliveryConfigName"),
          inline(environmentName).`as`("environmentName"),
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.CREATED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_BY,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.COMMENT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ATTRIBUTES
        )
        .from(ENVIRONMENT_ARTIFACT_CONSTRAINT)
        .where(
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID.eq(environmentUID),
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION.eq(artifactVersion),
          ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE.eq(type)
        )
        .let {
          // For backwards-compatibility, only use the artifact reference in the query when passed in, but also match
          // rows with a missing reference.
          if (artifactReference != null) {
            it.and(
              ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE.eq(artifactReference)
                .or(ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE.isNull)
            )
          } else {
            it
          }
        }
        .fetchOne { (
                      deliveryConfigName,
                      environmentName,
                      artifactVersion,
                      artifactReference,
                      constraintType,
                      status,
                      createdAt,
                      judgedBy,
                      judgedAt,
                      comment,
                      attributes
                    ) ->
          ConstraintState(
            deliveryConfigName,
            environmentName,
            artifactVersion,
            artifactReference,
            constraintType,
            status,
            createdAt,
            judgedBy,
            judgedAt,
            comment,
            objectMapper.readValue(attributes)
          )
        }
    }
  }

  override fun getConstraintStateById(uid: UID): ConstraintState? {
    return sqlRetry.withRetry(READ) {
      jooq
        .select(
          DELIVERY_CONFIG.NAME,
          ENVIRONMENT.NAME,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.CREATED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_BY,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.COMMENT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ATTRIBUTES
        )
        .from(ENVIRONMENT_ARTIFACT_CONSTRAINT, DELIVERY_CONFIG, ENVIRONMENT)
        .where(ENVIRONMENT_ARTIFACT_CONSTRAINT.UID.eq(uid.toString()))
        .and(ENVIRONMENT.UID.eq(ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID))
        .and(DELIVERY_CONFIG.UID.eq(ENVIRONMENT.DELIVERY_CONFIG_UID))
        .fetchOne { (
                      deliveryConfigName,
                      environmentName,
                      artifactVersion,
                      artifactReference,
                      constraintType,
                      status,
                      createdAt,
                      judgedBy,
                      judgedAt,
                      comment,
                      attributes
                    ) ->
          ConstraintState(
            deliveryConfigName,
            environmentName,
            artifactVersion,
            artifactReference,
            constraintType,
            status,
            createdAt,
            judgedBy,
            judgedAt,
            comment,
            objectMapper.readValue(attributes)
          )
        }
    }
  }

  override fun deleteConstraintState(
    deliveryConfigName: String,
    environmentName: String,
    type: String
  ) {
    val envUidSelect = envUid(deliveryConfigName, environmentName)
    sqlRetry.withRetry(WRITE) {
      jooq.select(CURRENT_CONSTRAINT.APPLICATION, CURRENT_CONSTRAINT.ENVIRONMENT_UID)
        .from(CURRENT_CONSTRAINT)
        .where(
          CURRENT_CONSTRAINT.ENVIRONMENT_UID.eq(envUidSelect),
          CURRENT_CONSTRAINT.TYPE.eq(type)
        )
        .fetch { (application, envUid) ->
          jooq.deleteFrom(CURRENT_CONSTRAINT)
            .where(
              CURRENT_CONSTRAINT.APPLICATION.eq(application),
              CURRENT_CONSTRAINT.ENVIRONMENT_UID.eq(envUid),
              CURRENT_CONSTRAINT.TYPE.eq(type)
            )
            .execute()
        }

      val ids: List<String> = jooq.select(ENVIRONMENT_ARTIFACT_CONSTRAINT.UID)
        .from(ENVIRONMENT_ARTIFACT_CONSTRAINT)
        .where(
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID.eq(envUidSelect),
          ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE.eq(type)
        )
        .fetch(ENVIRONMENT_ARTIFACT_CONSTRAINT.UID)
        .sorted()

      ids.chunked(DELETE_CHUNK_SIZE).forEach {
        jooq.deleteFrom(ENVIRONMENT_ARTIFACT_CONSTRAINT)
          .where(ENVIRONMENT_ARTIFACT_CONSTRAINT.UID.`in`(*it.toTypedArray()))
          .execute()
      }
    }
  }

  override fun constraintStateFor(application: String): List<ConstraintState> {
    val environmentNames = mutableMapOf<String, String>()
    val deliveryConfigsByEnv = mutableMapOf<String, String>()
    val constraintResult = sqlRetry.withRetry(READ) {
      jooq
        .select(
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.CREATED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_BY,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.COMMENT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ATTRIBUTES
        )
        .from(CURRENT_CONSTRAINT)
        .innerJoin(ENVIRONMENT_ARTIFACT_CONSTRAINT)
        .on(CURRENT_CONSTRAINT.CONSTRAINT_UID.eq(ENVIRONMENT_ARTIFACT_CONSTRAINT.UID))
        .where(CURRENT_CONSTRAINT.APPLICATION.eq(application))
        .fetch()
    }

    if (constraintResult.isEmpty()) {
      return emptyList()
    }

    sqlRetry.withRetry(READ) {
      jooq
        .select(
          CURRENT_CONSTRAINT.ENVIRONMENT_UID,
          ENVIRONMENT.NAME,
          DELIVERY_CONFIG.NAME
        )
        .from(CURRENT_CONSTRAINT)
        .innerJoin(ENVIRONMENT)
        .on(ENVIRONMENT.UID.eq(CURRENT_CONSTRAINT.ENVIRONMENT_UID))
        .innerJoin(DELIVERY_CONFIG)
        .on(DELIVERY_CONFIG.UID.eq(ENVIRONMENT.DELIVERY_CONFIG_UID))
        .where(CURRENT_CONSTRAINT.APPLICATION.eq(application))
        .fetch { (envId, envName, dcName) ->
          environmentNames[envId] = envName
          deliveryConfigsByEnv[envId] = dcName
        }
    }

    return constraintResult.mapNotNull { (
                                           envId,
                                           artifactVersion,
                                           artifactReference,
                                           type,
                                           createdAt,
                                           status,
                                           judgedBy,
                                           judgedAt,
                                           comment,
                                           attributes
                                         ) ->
      if (deliveryConfigsByEnv.containsKey(envId) && environmentNames.containsKey(envId)) {
        ConstraintState(
          deliveryConfigsByEnv[envId]
            ?: error("Environment id $envId does not belong to a delivery-config"),
          environmentNames[envId] ?: error("Invalid environment id $envId"),
          artifactVersion,
          artifactReference,
          type,
          status,
          createdAt,
          judgedBy,
          judgedAt,
          comment,
          objectMapper.readValue(attributes)
        )
      } else {
        log.warn(
          "constraint state for " +
            "envId=$envId, " +
            "artifactVersion=$artifactVersion, " +
            "type=$type, " +
            " does not belong to a valid environment."
        )
        null
      }
    }
  }

  override fun constraintStateFor(
    deliveryConfigName: String,
    environmentName: String,
    limit: Int
  ): List<ConstraintState> {
    val environmentUID = environmentUidByName(deliveryConfigName, environmentName)
      ?: return emptyList()
    return sqlRetry.withRetry(READ) {
      jooq
        .select(
          inline(deliveryConfigName).`as`("deliveryConfigName"),
          inline(environmentName).`as`("environmentName"),
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.CREATED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_BY,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.COMMENT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ATTRIBUTES
        )
        .from(ENVIRONMENT_ARTIFACT_CONSTRAINT)
        .where(ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID.eq(environmentUID))
        .orderBy(ENVIRONMENT_ARTIFACT_CONSTRAINT.CREATED_AT.desc())
        .limit(limit)
        .fetch { (
                   deliveryConfigName,
                   environmentName,
                   artifactVersion,
                   artifactReference,
                   constraintType,
                   status,
                   createdAt,
                   judgedBy,
                   judgedAt,
                   comment,
                   attributes
                 ) ->
          ConstraintState(
            deliveryConfigName,
            environmentName,
            artifactVersion,
            artifactReference,
            constraintType,
            status,
            createdAt,
            judgedBy,
            judgedAt,
            comment,
            objectMapper.readValue(attributes)
          )
        }
    }
  }

  override fun constraintStateFor(
    deliveryConfigName: String,
    environmentName: String,
    artifactVersion: String
  ): List<ConstraintState> {
    return constraintStateForWithTransaction(deliveryConfigName, environmentName, artifactVersion)
  }

  private fun constraintStateForWithTransaction(
    deliveryConfigName: String,
    environmentName: String,
    artifactVersion: String,
    txn: DSLContext = jooq
  ): List<ConstraintState> {
    val environmentUID = environmentUidByName(deliveryConfigName, environmentName)
      ?: return emptyList()

    return sqlRetry.withRetry(READ) {
      txn
        .select(
          inline(deliveryConfigName).`as`("deliveryConfigName"),
          inline(environmentName).`as`("environmentName"),
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_REFERENCE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.TYPE,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.CREATED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_BY,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.JUDGED_AT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.COMMENT,
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ATTRIBUTES
        )
        .from(ENVIRONMENT_ARTIFACT_CONSTRAINT)
        .where(
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID.eq(environmentUID),
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION.eq(artifactVersion)
        )
        .fetch { (
                   deliveryConfigName,
                   environmentName,
                   artifactVersion,
                   artifactReference,
                   constraintType,
                   status,
                   createdAt,
                   judgedBy,
                   judgedAt,
                   comment,
                   attributes
                 ) ->
          ConstraintState(
            deliveryConfigName,
            environmentName,
            artifactVersion,
            artifactReference,
            constraintType,
            status,
            createdAt,
            judgedBy,
            judgedAt,
            comment,
            objectMapper.readValue(attributes)
          )
        }
    }
  }

  override fun getPendingArtifactVersions(
    deliveryConfigName: String,
    environmentName: String,
    artifact: DeliveryArtifact
  ): List<PublishedArtifact> {
    val environmentUID = environmentUidByName(deliveryConfigName, environmentName)
      ?: return emptyList()

    return sqlRetry.withRetry(READ) {
      jooq
        .select(
          ARTIFACT_VERSIONS.NAME,
          ARTIFACT_VERSIONS.TYPE,
          ARTIFACT_VERSIONS.VERSION,
          ARTIFACT_VERSIONS.RELEASE_STATUS,
          ARTIFACT_VERSIONS.CREATED_AT,
          ARTIFACT_VERSIONS.GIT_METADATA,
          ARTIFACT_VERSIONS.BUILD_METADATA
        )
        .from(ENVIRONMENT_ARTIFACT_CONSTRAINT, ARTIFACT_VERSIONS)
        .where(
          ENVIRONMENT_ARTIFACT_CONSTRAINT.ENVIRONMENT_UID.eq(environmentUID),
          ENVIRONMENT_ARTIFACT_CONSTRAINT.STATUS.eq(ConstraintStatus.PENDING)
        )
        .and(ARTIFACT_VERSIONS.NAME.eq(artifact.name))
        .and(ARTIFACT_VERSIONS.TYPE.eq(artifact.type))
        .and(ARTIFACT_VERSIONS.VERSION.eq(ENVIRONMENT_ARTIFACT_CONSTRAINT.ARTIFACT_VERSION))
        .fetchSortedArtifactVersions(artifact)
    }
  }

  override fun getArtifactVersionsQueuedForApproval(
    deliveryConfigName: String,
    environmentName: String,
    artifact: DeliveryArtifact
  ): List<PublishedArtifact> {
    val environmentUID = environmentUidByName(deliveryConfigName, environmentName)
      ?: return emptyList()

    return sqlRetry.withRetry(READ) {
      jooq.select(
        ARTIFACT_VERSIONS.NAME,
        ARTIFACT_VERSIONS.TYPE,
        ARTIFACT_VERSIONS.VERSION,
        ARTIFACT_VERSIONS.RELEASE_STATUS,
        ARTIFACT_VERSIONS.CREATED_AT,
        ARTIFACT_VERSIONS.GIT_METADATA,
        ARTIFACT_VERSIONS.BUILD_METADATA
      )
        .from(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL, ARTIFACT_VERSIONS)
        .where(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ENVIRONMENT_UID.eq(environmentUID))
        .and(
          ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_REFERENCE.eq(artifact.reference)
            // for backward comparability
            .or(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_REFERENCE.isNull)
        )
        .and(ARTIFACT_VERSIONS.VERSION.eq(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_VERSION))
        .and(ARTIFACT_VERSIONS.NAME.eq(artifact.name))
        .and(ARTIFACT_VERSIONS.TYPE.eq(artifact.type))
        .fetchSortedArtifactVersions(artifact)
    }
  }

  /**
   * Not actually used because this is done in [storeConstraintState], except
   * in that place the envId does not have to be queried for.
   * It's also done as part of the existing transaction
   */
  override fun queueArtifactVersionForApproval(
    deliveryConfigName: String,
    environmentName: String,
    artifact: DeliveryArtifact,
    artifactVersion: String
  ) {
    sqlRetry.withRetry(WRITE) {
      environmentUidByName(deliveryConfigName, environmentName)
        ?.also { envUid ->
          jooq.insertInto(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL)
            .set(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ENVIRONMENT_UID, envUid)
            .set(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_VERSION, artifactVersion)
            .set(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.QUEUED_AT, clock.instant())
            .set(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_REFERENCE, artifact.reference)
            .onDuplicateKeyIgnore()
            .execute()
        }
    }
  }

  override fun deleteArtifactVersionQueuedForApproval(
    deliveryConfigName: String,
    environmentName: String,
    artifact: DeliveryArtifact,
    artifactVersion: String
  ) {
    sqlRetry.withRetry(WRITE) {
      environmentUidByName(deliveryConfigName, environmentName)
        ?.also { envUid ->
          jooq.deleteFrom(ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL)
            .where(
              ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ENVIRONMENT_UID.eq(envUid),
              ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_VERSION.eq(artifactVersion),
              ENVIRONMENT_ARTIFACT_QUEUED_APPROVAL.ARTIFACT_REFERENCE.eq(artifact.reference)
            )
            .execute()
        }
    }
  }

  override fun triggerRecheck(application: String) {
    val uid = sqlRetry.withRetry(READ) {
      jooq.select(DELIVERY_CONFIG.UID)
        .from(DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG.APPLICATION.eq(application))
        .fetchOne(DELIVERY_CONFIG.UID)
    }

    if (uid == null) {
      log.error("Config for app $application does not exist, cannot recheck it")
      return
    }

    sqlRetry.withRetry(WRITE) {
      jooq.update(DELIVERY_CONFIG_LAST_CHECKED)
        .set(DELIVERY_CONFIG_LAST_CHECKED.AT, EPOCH.plusSeconds(1))
        .set(DELIVERY_CONFIG_LAST_CHECKED.LEASED_AT, EPOCH.plusSeconds(1))
        .set(DELIVERY_CONFIG_LAST_CHECKED.LEASED_BY, RECHECK_LEASE_NAME)
        .where(DELIVERY_CONFIG_LAST_CHECKED.DELIVERY_CONFIG_UID.eq(uid))
        .execute()
    }
  }

  override fun itemsDueForCheck(
    minTimeSinceLastCheck: Duration,
    limit: Int
  ): Collection<DeliveryConfig> {
    val now = clock.instant()
    val cutoff = now.minus(minTimeSinceLastCheck)
    return sqlRetry.withRetry(WRITE) {
      jooq.inTransaction {
        select(DELIVERY_CONFIG.UID, DELIVERY_CONFIG.NAME)
          .from(DELIVERY_CONFIG, DELIVERY_CONFIG_LAST_CHECKED)
          .where(DELIVERY_CONFIG.UID.eq(DELIVERY_CONFIG_LAST_CHECKED.DELIVERY_CONFIG_UID))
          // has not been checked recently
          .and(DELIVERY_CONFIG_LAST_CHECKED.AT.lessOrEqual(cutoff))
          // either no other Keel instance is working on this, or the lease has expired (e.g. due to
          // instance termination mid-check)
          .and(
            DELIVERY_CONFIG_LAST_CHECKED.LEASED_BY.isNull
              .or(DELIVERY_CONFIG_LAST_CHECKED.LEASED_AT.lessOrEqual(cutoff))
          )
          // the application is not paused
          .andNotExists(
            selectOne()
              .from(PAUSED)
              .where(PAUSED.NAME.eq(DELIVERY_CONFIG.APPLICATION))
              .and(PAUSED.SCOPE.eq(APPLICATION))
          )
          .orderBy(DELIVERY_CONFIG_LAST_CHECKED.AT)
          .limit(limit)
          .forUpdate()
          .fetch()
          .also {
            it.forEach { (uid, _) ->
              update(DELIVERY_CONFIG_LAST_CHECKED)
                .set(DELIVERY_CONFIG_LAST_CHECKED.LEASED_BY, InetAddress.getLocalHost().hostName)
                .set(DELIVERY_CONFIG_LAST_CHECKED.LEASED_AT, now)
                .where(DELIVERY_CONFIG_LAST_CHECKED.DELIVERY_CONFIG_UID.eq(uid))
                .execute()
            }
          }
      }
    }
      .map { (_, name) ->
        get(name)
      }
  }

  override fun markCheckComplete(deliveryConfig: DeliveryConfig) {
    sqlRetry.withRetry(WRITE) {
      jooq
        .update(DELIVERY_CONFIG_LAST_CHECKED)
        // set last check time to now
        .set(DELIVERY_CONFIG_LAST_CHECKED.AT, clock.instant())
        // clear the lease to allow other instances to check this item
        .setNull(DELIVERY_CONFIG_LAST_CHECKED.LEASED_BY)
        .setNull(DELIVERY_CONFIG_LAST_CHECKED.LEASED_AT)
        .where(
          DELIVERY_CONFIG_LAST_CHECKED.DELIVERY_CONFIG_UID.eq(
            select(DELIVERY_CONFIG.UID)
              .from(DELIVERY_CONFIG)
              .where(DELIVERY_CONFIG.NAME.eq(deliveryConfig.name))
          )
        )
        .and(DELIVERY_CONFIG_LAST_CHECKED.LEASED_BY.ne(RECHECK_LEASE_NAME))
        .execute()
    }
  }

  private val String.uids: Select<Record1<String>>
    get() = select(RESOURCE.UID)
      .from(RESOURCE)
      .where(RESOURCE.ID.eq(this))

  private fun environmentUidByName(deliveryConfigName: String, environmentName: String): String? =
    sqlRetry.withRetry(READ) {
      jooq
        .select(ENVIRONMENT.UID)
        .from(DELIVERY_CONFIG)
        .innerJoin(ENVIRONMENT).on(DELIVERY_CONFIG.UID.eq(ENVIRONMENT.DELIVERY_CONFIG_UID))
        .where(
          DELIVERY_CONFIG.NAME.eq(deliveryConfigName),
          ENVIRONMENT.NAME.eq(environmentName)
        )
        .fetchOne(ENVIRONMENT.UID)
    }
      ?: null

  private fun envUid(deliveryConfigName: String, environmentName: String): Select<Record1<String>> =
    select(ENVIRONMENT.UID)
      .from(DELIVERY_CONFIG)
      .innerJoin(ENVIRONMENT).on(DELIVERY_CONFIG.UID.eq(ENVIRONMENT.DELIVERY_CONFIG_UID))
      .where(
        DELIVERY_CONFIG.NAME.eq(deliveryConfigName),
        ENVIRONMENT.NAME.eq(environmentName)
      )

  private fun envUidString(deliveryConfigName: String, environmentName: String): String? =
    sqlRetry.withRetry(READ) {
      jooq.select(ENVIRONMENT.UID)
        .from(DELIVERY_CONFIG)
        .innerJoin(ENVIRONMENT).on(DELIVERY_CONFIG.UID.eq(ENVIRONMENT.DELIVERY_CONFIG_UID))
        .where(
          DELIVERY_CONFIG.NAME.eq(deliveryConfigName),
          ENVIRONMENT.NAME.eq(environmentName)
        )
        .fetchOne(ENVIRONMENT.UID)
    }

  private fun applicationByDeliveryConfigName(name: String): String =
    sqlRetry.withRetry(READ) {
      jooq
        .select(DELIVERY_CONFIG.APPLICATION)
        .from(DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG.NAME.eq(name))
        .fetchOne(DELIVERY_CONFIG.APPLICATION)
    }
      ?: throw NoSuchDeliveryConfigName(name)

  override fun getApplicationSummaries(): Collection<ApplicationSummary> =
    sqlRetry.withRetry(READ) {
      jooq
        .select(
          DELIVERY_CONFIG.UID,
          DELIVERY_CONFIG.NAME,
          DELIVERY_CONFIG.APPLICATION,
          DELIVERY_CONFIG.SERVICE_ACCOUNT,
          DELIVERY_CONFIG.API_VERSION,
          count(RESOURCE.UID),
          PAUSED.NAME
        )
        .from(DELIVERY_CONFIG)
        .leftOuterJoin(RESOURCE).on(
          RESOURCE.APPLICATION.eq(DELIVERY_CONFIG.APPLICATION)
        )
        .leftOuterJoin(PAUSED).on(
          PAUSED.NAME.eq(DELIVERY_CONFIG.APPLICATION).and(PAUSED.SCOPE.eq(APPLICATION))
        )
        .groupBy(DELIVERY_CONFIG.APPLICATION)
        .orderBy(DELIVERY_CONFIG.APPLICATION)
        .fetch { (uid, name, application, serviceAccount, apiVersion, resourceCount, paused) ->
          ApplicationSummary(
            deliveryConfigName = name,
            application = application,
            serviceAccount = serviceAccount,
            apiVersion = apiVersion,
            createdAt = ULID.parseULID(uid).timestampAsInstant(),
            resourceCount = resourceCount,
            isPaused = paused != null
          )
        }
    }

  override fun deliveryConfigLastChecked(deliveryConfig: DeliveryConfig): Instant =
    sqlRetry.withRetry(READ) {
      jooq
        .select(DELIVERY_CONFIG_LAST_CHECKED.AT)
        .from(DELIVERY_CONFIG_LAST_CHECKED, DELIVERY_CONFIG)
        .where(DELIVERY_CONFIG_LAST_CHECKED.DELIVERY_CONFIG_UID.eq(DELIVERY_CONFIG.UID))
        .and(DELIVERY_CONFIG.NAME.eq(deliveryConfig.name))
        .fetchSingle(DELIVERY_CONFIG_LAST_CHECKED.AT) ?: EPOCH
    }

  companion object {
    private const val DELETE_CHUNK_SIZE = 20
  }
}
