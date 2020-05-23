/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.UnhappyVetoRepository
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.UNHAPPY_VETO
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import org.jooq.DSLContext

class SqlUnhappyVetoRepository(
  override val clock: Clock,
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry
) : UnhappyVetoRepository(clock) {

  override fun markUnhappyForWaitingTime(resourceId: String, application: String, wait: Duration?) {
    sqlRetry.withRetry(WRITE) {
      jooq.insertInto(UNHAPPY_VETO)
        .set(UNHAPPY_VETO.RESOURCE_ID, resourceId)
        .set(UNHAPPY_VETO.APPLICATION, application)
        .run {
          calculateExpirationTime(wait)?.toTimestamp().let { expiryTime ->
            if (expiryTime == null) {
              setNull(UNHAPPY_VETO.RECHECK_TIME)
            } else {
              set(UNHAPPY_VETO.RECHECK_TIME, expiryTime)
            }
          }
        }
        .onDuplicateKeyUpdate()
        .run {
          calculateExpirationTime(wait)?.toTimestamp().let { expiryTime ->
            if (expiryTime == null) {
              setNull(UNHAPPY_VETO.RECHECK_TIME)
            } else {
              set(UNHAPPY_VETO.RECHECK_TIME, expiryTime)
            }
          }
        }
        .execute()
    }
  }

  override fun delete(resourceId: String) {
    sqlRetry.withRetry(WRITE) {
      jooq.deleteFrom(UNHAPPY_VETO)
        .where(UNHAPPY_VETO.RESOURCE_ID.eq(resourceId))
        .execute()
    }
  }

  override fun getOrCreateVetoStatus(resourceId: String, application: String, wait: Duration?): UnhappyVetoStatus {
    sqlRetry.withRetry(READ) {
        jooq
          .select(UNHAPPY_VETO.RECHECK_TIME)
          .from(UNHAPPY_VETO)
          .where(UNHAPPY_VETO.RESOURCE_ID.eq(resourceId))
          .fetchOne()
      }
      ?.let { (recheckTime: LocalDateTime?) ->
        return UnhappyVetoStatus(
          shouldSkip = recheckTime == null || recheckTime > clock.timestamp(),
          shouldRecheck = recheckTime != null && recheckTime < clock.timestamp()
        )
      }

    markUnhappyForWaitingTime(resourceId, application, wait)
    return UnhappyVetoStatus(shouldSkip = true, shouldRecheck = false)
  }

  override fun getAll(): Set<String> {
    val now = clock.timestamp()
    return sqlRetry.withRetry(READ) {
      jooq.select(UNHAPPY_VETO.RESOURCE_ID)
        .from(UNHAPPY_VETO)
        .where(UNHAPPY_VETO.RECHECK_TIME.isNull.or(UNHAPPY_VETO.RECHECK_TIME.greaterOrEqual(now)))
        .fetch(UNHAPPY_VETO.RESOURCE_ID)
        .toSet()
    }
  }

  override fun getAllForApp(application: String): Set<String> {
    val now = clock.timestamp()
    return sqlRetry.withRetry(READ) {
      jooq.select(UNHAPPY_VETO.RESOURCE_ID)
        .from(UNHAPPY_VETO)
        .where(UNHAPPY_VETO.APPLICATION.eq(application))
        .and(UNHAPPY_VETO.RECHECK_TIME.isNull.or(UNHAPPY_VETO.RECHECK_TIME.greaterOrEqual(now)))
        .fetch(UNHAPPY_VETO.RESOURCE_ID)
        .toSet()
    }
  }
}
