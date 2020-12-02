package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.plugins.ArtifactSupplier
import com.netflix.spinnaker.keel.api.verification.VerificationContext
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.api.verification.VerificationState
import com.netflix.spinnaker.keel.api.verification.VerificationStatus
import com.netflix.spinnaker.keel.core.api.PromotionStatus.CURRENT
import com.netflix.spinnaker.keel.pause.PauseScope.APPLICATION
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DELIVERY_ARTIFACT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.DELIVERY_CONFIG
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT_ARTIFACT_VERSIONS
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT_LAST_VERIFIED
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.PAUSED
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.VERIFICATION_STATE
import com.netflix.spinnaker.keel.resources.ResourceSpecIdentifier
import com.netflix.spinnaker.keel.resources.SpecMigrator
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import com.netflix.spinnaker.keel.sql.deliveryconfigs.deliveryConfigByName
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.ResultQuery
import org.jooq.Select
import org.jooq.impl.DSL.isnull
import org.jooq.impl.DSL.select
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

class SqlVerificationRepository(
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
), VerificationRepository {

  override fun nextEnvironmentsForVerification(
    minTimeSinceLastCheck: Duration,
    limit: Int
  ): Collection<VerificationContext> {
    val now = clock.instant()
    val cutoff = now.minus(minTimeSinceLastCheck).toTimestamp()
    return sqlRetry.withRetry(WRITE) {
      jooq.inTransaction {
        select(
          DELIVERY_CONFIG.UID,
          DELIVERY_CONFIG.NAME,
          ENVIRONMENT.UID,
          ENVIRONMENT.NAME,
          ENVIRONMENT_ARTIFACT_VERSIONS.ARTIFACT_VERSION
        )
          .from(ENVIRONMENT)
          // join delivery config
          .join(DELIVERY_CONFIG)
          .on(DELIVERY_CONFIG.UID.eq(ENVIRONMENT.DELIVERY_CONFIG_UID))
          // the application is not paused
          .andNotExists(
            selectOne()
              .from(PAUSED)
              .where(PAUSED.NAME.eq(DELIVERY_CONFIG.APPLICATION))
              .and(PAUSED.SCOPE.eq(APPLICATION.name))
          )
          // join currently deployed artifact version
          .join(ENVIRONMENT_ARTIFACT_VERSIONS)
          .on(ENVIRONMENT_ARTIFACT_VERSIONS.ENVIRONMENT_UID.eq(ENVIRONMENT.UID))
          .and(ENVIRONMENT_ARTIFACT_VERSIONS.PROMOTION_STATUS.eq(CURRENT.name))
          // left join so we get results even if there is no row in ENVIRONMENT_LAST_VERIFIED
          .leftJoin(ENVIRONMENT_LAST_VERIFIED)
          .on(ENVIRONMENT_LAST_VERIFIED.ENVIRONMENT_UID.eq(ENVIRONMENT.UID))
          // has not been checked recently
          .where(isnull(ENVIRONMENT_LAST_VERIFIED.AT, LocalDateTime.MIN).lessOrEqual(cutoff))
          // order by last time checked with things never checked coming first
          .orderBy(isnull(ENVIRONMENT_LAST_VERIFIED.AT, LocalDateTime.MIN))
          .limit(limit)
          .forUpdate()
          .fetch()
          .onEach { (_, _, environmentUid, _, _) ->
            // TODO: this is not going to work right if there are multiple artifacts - need a 2nd FK on the table linking to artifact
            insertInto(ENVIRONMENT_LAST_VERIFIED)
              .set(ENVIRONMENT_LAST_VERIFIED.ENVIRONMENT_UID, environmentUid)
              .set(ENVIRONMENT_LAST_VERIFIED.AT, now.toTimestamp())
              .onDuplicateKeyUpdate()
              .set(ENVIRONMENT_LAST_VERIFIED.AT, now.toTimestamp())
              .execute()
          }
          .map { (_, deliveryConfigName, _, environmentName, artifactVersion) ->
            VerificationContext(
              deliveryConfigByName(deliveryConfigName),
              environmentName,
              artifactVersion
            )
          }
      }
    }
  }

  override fun getState(
    context: VerificationContext,
    verification: Verification
  ): VerificationState? =
    with(context) {
      jooq
        .select(
          VERIFICATION_STATE.STATUS,
          VERIFICATION_STATE.STARTED_AT,
          VERIFICATION_STATE.ENDED_AT
        )
        .from(VERIFICATION_STATE)
        .where(VERIFICATION_STATE.ENVIRONMENT_UID.eq(environmentUid))
        .and(VERIFICATION_STATE.ARTIFACT_UID.eq(artifact.uid))
        .and(VERIFICATION_STATE.ARTIFACT_VERSION.eq(version))
        .and(VERIFICATION_STATE.VERIFICATION_ID.eq(verification.id))
        .fetchOneInto<VerificationState>()
    }

  override fun updateState(
    context: VerificationContext,
    verification: Verification,
    status: VerificationStatus
  ) {
    with(context) {
      jooq
        .insertInto(VERIFICATION_STATE)
        .set(VERIFICATION_STATE.STATUS, status.name)
        .set(status.timestampColumn, currentTimestamp())
        .set(VERIFICATION_STATE.ENVIRONMENT_UID, environmentUid)
        .set(VERIFICATION_STATE.ARTIFACT_UID, artifact.uid)
        .set(VERIFICATION_STATE.ARTIFACT_VERSION, version)
        .set(VERIFICATION_STATE.VERIFICATION_ID, verification.id)
        .onDuplicateKeyUpdate()
        .set(VERIFICATION_STATE.STATUS, status.name)
        .set(status.timestampColumn, currentTimestamp())
        .execute()
    }
  }

  private inline fun <reified RESULT> ResultQuery<*>.fetchOneInto() =
    fetchOneInto(RESULT::class.java)

  private fun currentTimestamp() = clock.instant().toTimestamp()

  private val VerificationStatus.timestampColumn
    get() = if (complete) VERIFICATION_STATE.ENDED_AT else VERIFICATION_STATE.STARTED_AT

  private val VerificationContext.environmentUid: Select<Record1<String>>
    get() = select(ENVIRONMENT.UID)
      .from(DELIVERY_CONFIG, ENVIRONMENT)
      .where(DELIVERY_CONFIG.NAME.eq(deliveryConfig.name))
      .and(ENVIRONMENT.NAME.eq(environment.name))
      .and(ENVIRONMENT.DELIVERY_CONFIG_UID.eq(DELIVERY_CONFIG.UID))

  private val DeliveryArtifact.uid: Select<Record1<String>>
    get() = select(DELIVERY_ARTIFACT.UID)
      .from(DELIVERY_ARTIFACT)
      .where(DELIVERY_ARTIFACT.NAME.eq(name))
      .and(DELIVERY_ARTIFACT.TYPE.eq(type))
}
