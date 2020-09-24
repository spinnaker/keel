package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.UnhealthyVetoRepository
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.UNHEALTHY_VETO
import org.jooq.DSLContext
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.time.Instant

class SqlUnhealthyVetoRepository(
  override val clock: Clock,
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry
) : UnhealthyVetoRepository(clock){

  override fun isHealthy(resourceId: String): Boolean {
    val shouldRecheck = sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(UNHEALTHY_VETO.SHOULD_RECHECK)
        .from(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
        .fetchOne(UNHEALTHY_VETO.SHOULD_RECHECK)
    }

    return shouldRecheck == null || shouldRecheck
  }

  override fun markUnhealthy(resourceId: String, application: String) {
    val shouldRecheck = sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(UNHEALTHY_VETO.SHOULD_RECHECK)
        .from(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
        .fetchOne(UNHEALTHY_VETO.SHOULD_RECHECK)
    }

    if (shouldRecheck == null || shouldRecheck) {
      // record doesn't exist, or we're starting over in our calculation
      sqlRetry.withRetry(RetryCategory.WRITE) {
        jooq.insertInto(UNHEALTHY_VETO)
          .set(UNHEALTHY_VETO.RESOURCE_ID, resourceId)
          .set(UNHEALTHY_VETO.APPLICATION, application)
          .set(UNHEALTHY_VETO.TIME_DETECTED, clock.millis())
          .set(UNHEALTHY_VETO.SHOULD_RECHECK, false)
          .set(UNHEALTHY_VETO.NUM_TIMES_MARKED, 1)
          .onDuplicateKeyUpdate()
          .set(UNHEALTHY_VETO.TIME_DETECTED, clock.millis())
          .set(UNHEALTHY_VETO.SHOULD_RECHECK, false)
          .set(UNHEALTHY_VETO.NUM_TIMES_MARKED, 1)
          .execute()
      }
    } else {
      // we're not starting over, the resource is still unhealthy, increment the count
      sqlRetry.withRetry(RetryCategory.WRITE) {
        jooq.update(UNHEALTHY_VETO)
          .set(UNHEALTHY_VETO.NUM_TIMES_MARKED, UNHEALTHY_VETO.NUM_TIMES_MARKED.plus(1))
          .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
          .execute()
      }
    }
  }

  override fun getUnhealthyTime(resourceId: String): Instant? {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(UNHEALTHY_VETO.TIME_DETECTED)
        .from(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
        .fetchOne(UNHEALTHY_VETO.TIME_DETECTED)
        ?.let { Instant.ofEpochMilli(it) }
    }
  }

  override fun markHealthy(resourceId: String) {
    sqlRetry.withRetry(RetryCategory.WRITE) {
      jooq.update(UNHEALTHY_VETO)
        .set(UNHEALTHY_VETO.SHOULD_RECHECK, true)
        .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
        .execute()
    }
  }

  override fun delete(resourceId: String) {
    sqlRetry.withRetry(RetryCategory.WRITE) {
      jooq.deleteFrom(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
        .execute()
    }
  }

  override fun getAll(): Set<String> {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(UNHEALTHY_VETO.RESOURCE_ID)
        .from(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.SHOULD_RECHECK.eq(false))
        .fetch(UNHEALTHY_VETO.RESOURCE_ID)
        .toSet()
    }
  }

  override fun getAllForApp(application: String): Set<String> {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(UNHEALTHY_VETO.RESOURCE_ID)
        .from(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.APPLICATION.eq(application))
        .and(UNHEALTHY_VETO.SHOULD_RECHECK.eq(false))
        .fetch(UNHEALTHY_VETO.RESOURCE_ID)
        .toSet()
    }
  }

  //todo eb: test this
  @Scheduled(fixedDelayString = "\${keel.unhealthy.clean.frequency.millis:10800000}") // 3 hours
  fun cleanOldRecords() {
    // clean records that have been set healthy and are older than three hours
    val threeHoursAgo = clock.instant().minus(Duration.ofHours(3)).toEpochMilli()
    val numCleaned = sqlRetry.withRetry(RetryCategory.WRITE) {
      jooq.deleteFrom(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.SHOULD_RECHECK.eq(true))
        .and(UNHEALTHY_VETO.TIME_DETECTED.le(threeHoursAgo))
        .execute()
    }
    if (numCleaned > 0) {
        log.debug("Removed $numCleaned records from the unhealthy veto database")
      }
  }
}