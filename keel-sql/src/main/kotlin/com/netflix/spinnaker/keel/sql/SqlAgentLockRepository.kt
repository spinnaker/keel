package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.AgentLockRepository
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.AGENT_LOCK
import com.netflix.spinnaker.keel.scheduled.ScheduledAgent
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.vavr.control.Try
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jooq.DSLContext
import org.jooq.exception.SQLDialectNotSupportedException
import org.springframework.scheduling.annotation.Scheduled

class SqlAgentLockRepository(
  private val jooq: DSLContext,
  private val clock: Clock,
  private val retryProperties: RetryProperties,
  private val agents: List<ScheduledAgent>
) : AgentLockRepository, CoroutineScope {

  override val coroutineContext: CoroutineContext = Dispatchers.IO

  @Scheduled(fixedDelayString = "\${keel.scheduled.agent.frequency:PT10S}")
  fun invokeAgent() {
    agents.forEach {
      val lockAcquired = tryAcquireLock(it.javaClass.simpleName, it.lockTimeoutSeconds)
      if (lockAcquired) {

        val job = launch {
          it.invokeAgent()
        }
        runBlocking { job.join()
        }
      }
    }
  }

  override fun tryAcquireLock(agentName: String, lockTimeoutSeconds: Long): Boolean {
    val now = clock.instant()

    var changed = withRetry {
      jooq.insertInto(AGENT_LOCK)
        .set(AGENT_LOCK.LOCK_NAME, agentName)
        .set(AGENT_LOCK.EXPIRY, now.plusSeconds(lockTimeoutSeconds).toEpochMilli())
        .onDuplicateKeyIgnore()
        .execute()
    }

    if (changed == 0) {
      changed = withRetry {
        jooq.update(AGENT_LOCK)
          .set(AGENT_LOCK.EXPIRY, now.plusSeconds(lockTimeoutSeconds).toEpochMilli())
          .where(
            AGENT_LOCK.LOCK_NAME.eq(agentName),
            AGENT_LOCK.EXPIRY.lt(now.toEpochMilli())
          )
          .execute()
      }
    }

    return changed == 1
  }

  private fun <T> withRetry(action: () -> T): T {
    val retry = Retry.of(
      "sqlWrite",
      RetryConfig.custom<T>()
        .maxAttempts(retryProperties.maxRetries)
        .waitDuration(Duration.ofMillis(retryProperties.backoffMs))
        .ignoreExceptions(SQLDialectNotSupportedException::class.java)
        .build()
    )

    return Try.ofSupplier(Retry.decorateSupplier(retry, action)).get()
  }
}
