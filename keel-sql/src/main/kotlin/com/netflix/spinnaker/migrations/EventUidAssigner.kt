package com.netflix.spinnaker.migrations

import com.netflix.spinnaker.keel.persistence.metamodel.Tables.EVENT
import com.netflix.spinnaker.keel.sql.inTransaction
import de.huxhorn.sulky.ulid.ULID
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.Result
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

class EventUidAssigner(private val jooq: DSLContext) : CoroutineScope {

  private val idGenerator = ULID()
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @EventListener(ApplicationReadyEvent::class)
  fun onApplicationReady() {
    launch {
      var done = false
      while (!done) {
        var count = 0
        runCatching {
          jooq.inTransaction {
            fetchEventBatch()
              .also {
                done = it.isEmpty()
              }
              .forEach { (ts) ->
                runCatching { assignUID(ts) }
                  .onSuccess { count += it }
                  .onFailure { ex ->
                    log.error("Error assigning uid to event with timestamp $ts", ex)
                  }
              }
          }
        }
          .onFailure { ex ->
            log.error("Error selecting event batch to assign uids", ex)
          }
        log.info("Assigned uids to $count events...")
      }
      log.info("All events have uids assigned")
    }
  }

  private fun DSLContext.fetchEventBatch(batchSize: Int = 100): Result<Record1<LocalDateTime>> =
    select(EVENT.TIMESTAMP)
      .from(EVENT)
      .where(EVENT.UID.isNull)
      .limit(batchSize)
      .fetch()

  private fun DSLContext.assignUID(timestamp: LocalDateTime): Int =
    update(EVENT)
      .set(EVENT.UID, timestamp.nextULID())
      // this might actually not update the same row, but 🤷‍
      .where(EVENT.TIMESTAMP.eq(timestamp))
      .and(EVENT.UID.isNull)
      .limit(1)
      .execute()

  private fun LocalDateTime.nextULID(): String =
    toInstant(UTC)
      .toEpochMilli()
      .let { idGenerator.nextULID(it) }

  override val coroutineContext = Dispatchers.IO
}
