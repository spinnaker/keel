package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType
import com.netflix.spinnaker.keel.lifecycle.LifecycleMonitorRepository
import com.netflix.spinnaker.keel.lifecycle.MonitoredTask
import com.netflix.spinnaker.keel.persistence.metamodel.tables.LifecycleMonitor.LIFECYCLE_MONITOR
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import de.huxhorn.sulky.ulid.ULID
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

class SqlLifecycleMonitorRepository(
  private val jooq: DSLContext,
  private val clock: Clock,
  private val objectMapper: ObjectMapper,
  private val sqlRetry: SqlRetry
) : LifecycleMonitorRepository {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun tasksDueForCheck(type: LifecycleEventType, minTimeSinceLastCheck: Duration, limit: Int): Collection<MonitoredTask> {
    val now = clock.instant()
    val cutoff = now.minus(minTimeSinceLastCheck).toTimestamp()
    return sqlRetry.withRetry(WRITE) {
      jooq.inTransaction {
        select(LIFECYCLE_MONITOR.UID, LIFECYCLE_MONITOR.TYPE, LIFECYCLE_MONITOR.LINK, LIFECYCLE_MONITOR.TRIGGERING_EVENT, LIFECYCLE_MONITOR.NUM_FAILURES)
          .from(LIFECYCLE_MONITOR)
          .where(LIFECYCLE_MONITOR.TYPE.eq(type.name))
          .and(LIFECYCLE_MONITOR.LAST_CHECKED.lessOrEqual(cutoff))
          .and(LIFECYCLE_MONITOR.IGNORE.notEqual(true))
          .orderBy(LIFECYCLE_MONITOR.LAST_CHECKED)
          .limit(limit)
          .forUpdate()
          .fetch()
          .also {
            it.forEach { (uid, _, _, _, _) ->
              update(LIFECYCLE_MONITOR)
                .set(LIFECYCLE_MONITOR.LAST_CHECKED, now.toTimestamp())
                .where(LIFECYCLE_MONITOR.UID.eq(uid))
                .execute()
            }
          }
      }
        .map { (uid, type, link, event, numFailures ) ->
          try {
            MonitoredTask(
              type = LifecycleEventType.valueOf(type),
              link = link,
              triggeringEvent = objectMapper.readValue(event),
              numFailures = numFailures
            )
          } catch (e: Exception) {
            // if we can't serialize the event, ignore it so it doesn't block future things
            jooq.update(LIFECYCLE_MONITOR)
              .set(LIFECYCLE_MONITOR.IGNORE, true)
              .where(LIFECYCLE_MONITOR.UID.eq(uid))
              .execute()
            throw e
          }
        }
    }
  }

  override fun save(task: MonitoredTask) {
    sqlRetry.withRetry(WRITE) {
      jooq.insertInto(LIFECYCLE_MONITOR)
        .set(LIFECYCLE_MONITOR.UID, ULID().nextULID(clock.millis()))
        .set(LIFECYCLE_MONITOR.TYPE, task.type.name)
        .set(LIFECYCLE_MONITOR.LINK, task.link)
        .set(LIFECYCLE_MONITOR.LAST_CHECKED, Instant.EPOCH.plusSeconds(1).toTimestamp())
        .set(LIFECYCLE_MONITOR.TRIGGERING_EVENT, objectMapper.writeValueAsString(task.triggeringEvent))
        .execute()
    }
  }

  override fun delete(task: MonitoredTask) {
    sqlRetry.withRetry(WRITE) {
      jooq.deleteFrom(LIFECYCLE_MONITOR)
        .where(LIFECYCLE_MONITOR.TYPE.eq(task.type.name))
        .and(LIFECYCLE_MONITOR.LINK.eq(task.link))
        .execute()
    }
  }

  override fun markFailureGettingStatus(task: MonitoredTask) {
    sqlRetry.withRetry(WRITE) {
      jooq.update(LIFECYCLE_MONITOR)
        .set(LIFECYCLE_MONITOR.NUM_FAILURES,LIFECYCLE_MONITOR.NUM_FAILURES.plus(1))
        .where(LIFECYCLE_MONITOR.TYPE.eq(task.type.name))
        .and(LIFECYCLE_MONITOR.LINK.eq(task.link))
        .execute()
    }
  }

  override fun numTasksMonitoring(): Int =
    sqlRetry.withRetry(RetryCategory.READ) {
      jooq.selectCount()
        .from(LIFECYCLE_MONITOR)
        .fetchOne()
        .value1()
    }
}
