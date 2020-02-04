package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.AgentLockRepository
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.AGENT_LOCK
import com.netflix.spinnaker.keel.scheduled.ScheduledAgent
import java.time.Clock
import org.jooq.DSLContext

class SqlAgentLockRepository(
  private val jooq: DSLContext,
  private val clock: Clock,
  override val agents: List<ScheduledAgent>,
  private val sqlRetry: SqlRetry
) : AgentLockRepository {

  // override val coroutineContext: CoroutineContext = Dispatchers.IO
//  private val log by lazy { LoggerFactory.getLogger(javaClass) }

//  private var enabled = false
//
//  @EventListener(ApplicationUp::class)
//  fun onApplicationUp() {
//    log.info("Application up, enabling scheduled agents")
//    enabled = true
//  }
//
//  @EventListener(ApplicationDown::class)
//  fun onApplicationDown() {
//    log.info("Application down, disabling scheduled agents")
//    enabled = false
//  }
//
//  @Scheduled(fixedDelayString = "\${keel.scheduled.agent.frequency:PT1M}")
//  fun invokeAgent() {
//    if (enabled) {
//      agents.forEach {
//        val agentName: String = it.javaClass.simpleName
//        val lockAcquired = tryAcquireLock(agentName, it.lockTimeoutSeconds)
//        if (lockAcquired) {
//          val job = launch {
//            it.invokeAgent()
//          }
//          runBlocking {
//            job.join()
//          }
//          log.debug("invoking $agentName completed")
//        }
//      }
//    } else {
//      log.debug("invoking agent disabled")
//    }
//  }

  override fun tryAcquireLock(agentName: String, lockTimeoutSeconds: Long): Boolean {
    val now = clock.instant()

    var changed = sqlRetry.withRetry(RetryCategory.WRITE) {
      jooq.insertInto(AGENT_LOCK)
        .set(AGENT_LOCK.LOCK_NAME, agentName)
        .set(AGENT_LOCK.EXPIRY, now.plusSeconds(lockTimeoutSeconds).toEpochMilli())
        .onDuplicateKeyIgnore()
        .execute()
    }

    if (changed == 0) {
      changed = sqlRetry.withRetry(RetryCategory.WRITE) {
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

  override fun getLockedAgents(): List<String> {
    return sqlRetry.withRetry(RetryCategory.READ) {
      jooq.select(AGENT_LOCK.LOCK_NAME)
        .from(AGENT_LOCK)
        .fetch(AGENT_LOCK.LOCK_NAME)
    }
  }
}
