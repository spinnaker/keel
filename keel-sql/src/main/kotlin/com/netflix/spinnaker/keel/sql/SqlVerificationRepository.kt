package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.api.plugins.ArtifactSupplier
import com.netflix.spinnaker.keel.api.verification.VerificationContext
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.api.verification.VerificationState
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
import org.jooq.Field
import org.jooq.Record1
import org.jooq.Record4
import org.jooq.Select
import org.jooq.SelectOrderByStep
import org.jooq.impl.DSL.function
import org.jooq.Table
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.inline
import org.jooq.impl.DSL.isnull
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.select
import org.jooq.impl.DSL.value
import java.time.Clock
import java.time.Duration
import java.time.Instant.EPOCH

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
    val cutoff = now.minus(minTimeSinceLastCheck)
    return sqlRetry.withRetry(WRITE) {
      // TODO: only consider environments that have verifications
      jooq.inTransaction {
        select(
          DELIVERY_CONFIG.UID,
          DELIVERY_CONFIG.NAME,
          ENVIRONMENT.UID,
          ENVIRONMENT.NAME,
          DELIVERY_ARTIFACT.UID,
          DELIVERY_ARTIFACT.REFERENCE,
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
              .and(PAUSED.SCOPE.eq(APPLICATION))
          )
          // join currently deployed artifact version
          .join(ENVIRONMENT_ARTIFACT_VERSIONS)
          .on(ENVIRONMENT_ARTIFACT_VERSIONS.ENVIRONMENT_UID.eq(ENVIRONMENT.UID))
          .and(ENVIRONMENT_ARTIFACT_VERSIONS.PROMOTION_STATUS.eq(CURRENT))
          // join artifact
          .join(DELIVERY_ARTIFACT)
          .on(DELIVERY_ARTIFACT.UID.eq(ENVIRONMENT_ARTIFACT_VERSIONS.ARTIFACT_UID))
          // left join so we get results even if there is no row in ENVIRONMENT_LAST_VERIFIED
          .leftJoin(ENVIRONMENT_LAST_VERIFIED)
          .on(ENVIRONMENT_LAST_VERIFIED.ENVIRONMENT_UID.eq(ENVIRONMENT.UID))
          .and(ENVIRONMENT_LAST_VERIFIED.ARTIFACT_UID.eq(DELIVERY_ARTIFACT.UID))
          .and(ENVIRONMENT_LAST_VERIFIED.ARTIFACT_VERSION.eq(ENVIRONMENT_ARTIFACT_VERSIONS.ARTIFACT_VERSION))
          // has not been checked recently (or has never been checked)
          .where(isnull(ENVIRONMENT_LAST_VERIFIED.AT, EPOCH).lessOrEqual(cutoff))
          // order by last time checked with things never checked coming first
          .orderBy(isnull(ENVIRONMENT_LAST_VERIFIED.AT, EPOCH))
          .limit(limit)
          .fetch()
          .onEach { (_, _, environmentUid, _, artifactUid, _, artifactVersion) ->
            insertInto(ENVIRONMENT_LAST_VERIFIED)
              .set(ENVIRONMENT_LAST_VERIFIED.ENVIRONMENT_UID, environmentUid)
              .set(ENVIRONMENT_LAST_VERIFIED.ARTIFACT_UID, artifactUid)
              .set(ENVIRONMENT_LAST_VERIFIED.ARTIFACT_VERSION, artifactVersion)
              .set(ENVIRONMENT_LAST_VERIFIED.AT, now)
              .onDuplicateKeyUpdate()
              .set(ENVIRONMENT_LAST_VERIFIED.ARTIFACT_VERSION, artifactVersion)
              .set(ENVIRONMENT_LAST_VERIFIED.AT, now)
              .execute()
          }
      }
    }
      .map { (_, deliveryConfigName, _, environmentName, _, artifactReference, artifactVersion) ->
        VerificationContext(
          deliveryConfigByName(deliveryConfigName),
          environmentName,
          artifactReference,
          artifactVersion
        )
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
          VERIFICATION_STATE.ENDED_AT,
          VERIFICATION_STATE.METADATA
        )
        .from(VERIFICATION_STATE)
        .where(VERIFICATION_STATE.ENVIRONMENT_UID.eq(environmentUid))
        .and(VERIFICATION_STATE.ARTIFACT_UID.eq(artifactUid))
        .and(VERIFICATION_STATE.ARTIFACT_VERSION.eq(version))
        .and(VERIFICATION_STATE.VERIFICATION_ID.eq(verification.id))
        .fetchOneInto<VerificationState>()
    }

  override fun getStates(context: VerificationContext): Map<String, VerificationState> =
    with(context) {
      when {
        verifications.isEmpty() -> emptyMap() // Optimization: don't hit the db if we know there are no entries
        else -> jooq.select(
          VERIFICATION_STATE.VERIFICATION_ID,
          VERIFICATION_STATE.STATUS,
          VERIFICATION_STATE.STARTED_AT,
          VERIFICATION_STATE.ENDED_AT,
          VERIFICATION_STATE.METADATA
        )
          .from(VERIFICATION_STATE)
          .where(VERIFICATION_STATE.ENVIRONMENT_UID.eq(environmentUid))
          .and(VERIFICATION_STATE.ARTIFACT_UID.eq(artifactUid))
          .and(VERIFICATION_STATE.ARTIFACT_VERSION.eq(version))
          .fetch()
          .associate { (id, status, started_at, ended_at, metadata) ->
            id to VerificationState(status, started_at, ended_at, metadata)
          }
      }
    }

  /**
   * Query the repository for the states of multiple contexts.
   *
   * This call is semantically equivalent to
   *    contexts.map { context -> this.getStates(context) }
   *
   * However, it's implemented as a single query for efficiency.
   *
   * @param contexts a list of verification contexts to query for state
   * @return a list of maps of verification ids to states, in the same order as the contexts. If there are no
   *         verification states associated with a context, the resulting map will be empty.
   */
  override fun getStatesBatch(contexts: List<VerificationContext>) : List<Map<String, VerificationState>> {
    /**
     * In-memory database table representation of the set of contexts we want to query for
     *
     * Columns:
     *   ind - index that encodes the original list order, to ensure results are in same order
     *   environment_name
     *   artifact_reference
     *   artifact_version
     */
    val contextTable = ContextTable(contexts, jooq)

    /**
     * This function guarantees that the number of output elements match the number of input elements.
     * So we use left joins on the givenVersions table
     */
    return jooq.select(

      contextTable.IND,
      VERIFICATION_STATE.VERIFICATION_ID,
      VERIFICATION_STATE.STATUS,
      VERIFICATION_STATE.STARTED_AT,
      VERIFICATION_STATE.ENDED_AT,
      VERIFICATION_STATE.METADATA)
      .from(contextTable.table)
      .leftJoin(ENVIRONMENT)
      .on(ENVIRONMENT.NAME.eq(contextTable.ENVIRONMENT_NAME))
      .leftJoin(DELIVERY_CONFIG)
      .on(DELIVERY_CONFIG.UID.eq(ENVIRONMENT.DELIVERY_CONFIG_UID))
      .leftJoin(DELIVERY_ARTIFACT)
      .on(DELIVERY_ARTIFACT.REFERENCE.eq(contextTable.ARTIFACT_REFERENCE))
      .leftJoin(VERIFICATION_STATE)
      .on(VERIFICATION_STATE.ARTIFACT_UID.eq(DELIVERY_ARTIFACT.UID))
      .and(VERIFICATION_STATE.ARTIFACT_VERSION.eq(contextTable.ARTIFACT_VERSION))
      .and(DELIVERY_ARTIFACT.DELIVERY_CONFIG_NAME.eq(DELIVERY_CONFIG.NAME))
      .and(VERIFICATION_STATE.ENVIRONMENT_UID.eq(ENVIRONMENT.UID))

      // execute the query
      .fetch()

      // sort the results by the "ind" (index) column, so that outputs are same order as inputs
      .groupBy { (index, _, _, _, _, _) -> index as Long }
      .toSortedMap()
      .values

      // convert List<Record> to Map<String,VerificationState>, where the string is the verification id
      .map { records ->
        records
          // since we do a left join, there may be rows where there is no corresponding records in the
          // verification_state database, so we filter them out, which will result in an empty map
          .filter {(_, _, status, _, _, _) -> status != null }
          .associate { (_, verification_id, status, started_at, ended_at, metadata) ->
            verification_id to VerificationState(status, started_at, ended_at, metadata)
          }
      }
      .toList()
  }


  override fun updateState(
    context: VerificationContext,
    verification: Verification,
    status: ConstraintStatus,
    metadata: Map<String, Any?>
  ) {
    with(context) {
      jooq
        .insertInto(VERIFICATION_STATE)
        .set(VERIFICATION_STATE.STATUS, status)
        .set(VERIFICATION_STATE.METADATA, metadata)
        .set(VERIFICATION_STATE.STARTED_AT, currentTimestamp())
        .run {
          if (status.complete) {
            set(VERIFICATION_STATE.ENDED_AT, currentTimestamp())
          } else {
            setNull(VERIFICATION_STATE.ENDED_AT)
          }
        }
        .set(VERIFICATION_STATE.ENVIRONMENT_UID, environmentUid)
        .set(VERIFICATION_STATE.ARTIFACT_UID, artifactUid)
        .set(VERIFICATION_STATE.ARTIFACT_VERSION, version)
        .set(VERIFICATION_STATE.VERIFICATION_ID, verification.id)
        .onDuplicateKeyUpdate()
        .set(VERIFICATION_STATE.STATUS, status)
        .run {
          if (status.complete) {
            set(VERIFICATION_STATE.ENDED_AT, currentTimestamp())
          } else {
            setNull(VERIFICATION_STATE.ENDED_AT)
          }
        }
        .run {
          if (metadata.isNotEmpty()) {
            set(VERIFICATION_STATE.METADATA, jsonMerge(VERIFICATION_STATE.METADATA, metadata))
          } else {
            this
          }
        }
        .execute()
    }
  }

  /**
   * JOOQ-ified access to MySQL's `json_merge` function.
   *
   * Updates [field] with [values] retaining any existing entries that are not present in [values].
   * Any array properties are appended to existing arrays.
   *
   * @see https://dev.mysql.com/doc/refman/8.0/en/json-modification-functions.html#function_json-merge
   */
  @Suppress("UNCHECKED_CAST")
  private fun jsonMerge(field: Field<Map<String, Any?>>, values: Map<String, Any?>) =
    function<Map<String, Any?>>(
      "json_merge",
      field,
      value(objectMapper.writeValueAsString(values))
    )

  private inline fun <reified T> function(name: String, vararg arguments: Field<*>) =
    function(name, T::class.java, *arguments)

  private fun currentTimestamp() = clock.instant()

  private val VerificationContext.environmentUid: Select<Record1<String>>
    get() = select(ENVIRONMENT.UID)
      .from(DELIVERY_CONFIG, ENVIRONMENT)
      .where(DELIVERY_CONFIG.NAME.eq(deliveryConfig.name))
      .and(ENVIRONMENT.NAME.eq(environment.name))
      .and(ENVIRONMENT.DELIVERY_CONFIG_UID.eq(DELIVERY_CONFIG.UID))

  private val VerificationContext.artifactUid: Select<Record1<String>>
    get() = select(DELIVERY_ARTIFACT.UID)
      .from(DELIVERY_ARTIFACT)
      .where(DELIVERY_ARTIFACT.DELIVERY_CONFIG_NAME.eq(deliveryConfig.name))
      .and(DELIVERY_ARTIFACT.REFERENCE.eq(artifactReference))


  /**
   * Helper class for [getStatesBatch]
   */
  @Suppress("PropertyName")
  private class ContextTable(
    val contexts: List<VerificationContext>,
    val jooq: DSLContext
  ) {
    val alias = "verification_contexts"

    private val ind = "ind"
    private val environmentName = "environment_name"
    private val artifactReference = "artifact_reference"
    private val artifactVersion = "artifact_version"

    fun <T> typedField(s : String, t: Class<T>) : Field<T> = field(name(alias, s), t)

    // These behave like regular jOOQ table field names when building SQL queries

    val IND  = f(ind, Long::class.java)
    val ENVIRONMENT_NAME = f(environmentName, String::class.java)
    val ARTIFACT_REFERENCE = f(artifactReference, String::class.java)
    val ARTIFACT_VERSION = f(artifactVersion, String::class.java)

    /**
     * return a jOOQ table that contains the [contexts] data represented as a table that can be selected against
     */
    val table : Table<Record4<Int, String, String, String>>
      get() =
        contexts
          .mapIndexed { idx, v -> jooq.select(inline(idx), inline(v.environmentName), inline(v.artifactReference), inline(v.version)) as SelectOrderByStep<Record4<Int, String, String, String>> }
          .reduce { s1, s2 -> s1.unionAll(s2) }
          .asTable(alias, ind, environmentName, artifactReference, artifactVersion)
  }

}
