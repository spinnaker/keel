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

import com.netflix.spinnaker.keel.persistence.PausedRepository
import com.netflix.spinnaker.keel.persistence.PausedRepository.Scope.APPLICATION
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.PAUSED
import org.jooq.DSLContext

class SqlPausedRepository(
  val jooq: DSLContext
) : PausedRepository {

  override fun pauseApplication(application: String) {
    jooq
      .insertInto(PAUSED)
      .set(PAUSED.SCOPE, APPLICATION.name)
      .set(PAUSED.NAME, application)
      .onDuplicateKeyIgnore()
      .execute()
  }

  override fun resumeApplication(application: String) {
    jooq
      .deleteFrom(PAUSED)
      .where(PAUSED.SCOPE.eq(APPLICATION.name))
      .and(PAUSED.NAME.eq(application))
      .execute()
  }

  override fun applicationIsPaused(application: String): Boolean {
    jooq
      .select(PAUSED.NAME)
      .from(PAUSED)
      .where(PAUSED.SCOPE.eq(APPLICATION.name))
      .and(PAUSED.NAME.eq(application))
      .fetchOne()
      ?.let { return true }
    return false
  }

  override fun pausedApplications(): List<String> =
    jooq
      .select(PAUSED.NAME)
      .from(PAUSED)
      .where(PAUSED.SCOPE.eq(APPLICATION.name))
      .fetch(PAUSED.NAME)
}
