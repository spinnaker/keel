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
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.selectCount()
        .from(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
        .fetchOne()
        .value1()
    } == 0
  }

  override fun markAllowed(resourceId: String) {
    sqlRetry.withRetry(RetryCategory.WRITE) {
      jooq.update(UNHEALTHY_VETO)
        .set(UNHEALTHY_VETO.LAST_TIME_ALLOWED, clock.millis())
        .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
        .execute()
    }
  }

  override fun markUnhealthy(resourceId: String, application: String) {
      sqlRetry.withRetry(RetryCategory.WRITE) {
        jooq.insertInto(UNHEALTHY_VETO)
          .set(UNHEALTHY_VETO.RESOURCE_ID, resourceId)
          .set(UNHEALTHY_VETO.APPLICATION, application)
          .set(UNHEALTHY_VETO.TIME_DETECTED, clock.millis())
          .set(UNHEALTHY_VETO.LAST_TIME_ALLOWED, clock.millis())
          .set(UNHEALTHY_VETO.NUM_TIMES_MARKED, 1)
          .onDuplicateKeyUpdate()
          .set(UNHEALTHY_VETO.NUM_TIMES_MARKED, UNHEALTHY_VETO.NUM_TIMES_MARKED.plus(1))
          .execute()
      }
  }

  override fun getLastALlowedTime(resourceId: String): Instant? {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(UNHEALTHY_VETO.LAST_TIME_ALLOWED)
        .from(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.RESOURCE_ID.eq(resourceId))
        .fetchOne(UNHEALTHY_VETO.LAST_TIME_ALLOWED)
        ?.let { Instant.ofEpochMilli(it) }
    }
  }

  override fun markHealthy(resourceId: String) {
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
        .fetch(UNHEALTHY_VETO.RESOURCE_ID)
        .toSet()
    }
  }

  override fun getAllForApp(application: String): Set<String> {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(UNHEALTHY_VETO.RESOURCE_ID)
        .from(UNHEALTHY_VETO)
        .where(UNHEALTHY_VETO.APPLICATION.eq(application))
        .fetch(UNHEALTHY_VETO.RESOURCE_ID)
        .toSet()
    }
  }
}
