package com.netflix.spinnaker.keel.bakehistory.sql

import com.netflix.spinnaker.keel.bakery.artifact.BakeHistory
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.BAKE_HISTORY
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import com.netflix.spinnaker.keel.sql.SqlRetry
import java.time.Clock
import java.time.ZoneOffset.UTC
import org.jooq.DSLContext

class SqlBakeHistory(
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry,
  private val clock: Clock = Clock.systemUTC()
) : BakeHistory {
  override fun contains(appVersion: String, baseAmiVersion: String): Boolean =
    jooq
      .selectOne()
      .from(BAKE_HISTORY)
      .where(BAKE_HISTORY.APP_VERSION.eq(appVersion))
      .and(BAKE_HISTORY.BASE_AMI_VERSION.eq(baseAmiVersion))
      .fetch()
      .isNotEmpty

  override fun add(appVersion: String, baseAmiVersion: String, regions: Collection<String>, taskId: String) {
    sqlRetry.withRetry(WRITE) {
      jooq
        .insertInto(BAKE_HISTORY)
        .set(BAKE_HISTORY.APP_VERSION, appVersion)
        .set(BAKE_HISTORY.BASE_AMI_VERSION, baseAmiVersion)
        .set(BAKE_HISTORY.REGIONS, regions.joinToString())
        .set(BAKE_HISTORY.TASK_ID, taskId)
        .set(BAKE_HISTORY.LAUNCHED_AT, clock.instant().atOffset(UTC).toLocalDateTime())
        .onDuplicateKeyIgnore()
        .execute()
    }
  }
}
