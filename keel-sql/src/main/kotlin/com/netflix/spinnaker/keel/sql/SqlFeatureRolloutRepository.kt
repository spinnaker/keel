package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.FeatureRolloutRepository
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.FEATURE_ROLLOUT
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import org.jooq.DSLContext
import java.time.Clock

class SqlFeatureRolloutRepository(
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry,
  private val clock: Clock
) : FeatureRolloutRepository {
  override fun markRolloutStarted(feature: String, resourceId: String) {
    sqlRetry.withRetry(WRITE) {
      jooq
        .insertInto(FEATURE_ROLLOUT)
        .set(FEATURE_ROLLOUT.FEATURE, feature)
        .set(FEATURE_ROLLOUT.RESOURCE_ID, resourceId)
        .set(FEATURE_ROLLOUT.ATTEMPTS, 1)
        .set(FEATURE_ROLLOUT.FIRST_ATTEMPT_AT, clock.instant())
        .set(FEATURE_ROLLOUT.LATEST_ATTEMPT_AT, clock.instant())
        .onDuplicateKeyUpdate()
        .set(FEATURE_ROLLOUT.ATTEMPTS, FEATURE_ROLLOUT.ATTEMPTS + 1)
        .set(FEATURE_ROLLOUT.LATEST_ATTEMPT_AT, clock.instant())
        .execute()
    }
  }

  override fun countRolloutAttempts(feature: String, resourceId: String): Int =
    sqlRetry.withRetry(READ) {
      jooq
        .select(FEATURE_ROLLOUT.ATTEMPTS)
        .from(FEATURE_ROLLOUT)
        .where(FEATURE_ROLLOUT.FEATURE.eq(feature))
        .and(FEATURE_ROLLOUT.RESOURCE_ID.eq(resourceId))
        .fetchOneInto<Int>() ?: 0
    }
}
