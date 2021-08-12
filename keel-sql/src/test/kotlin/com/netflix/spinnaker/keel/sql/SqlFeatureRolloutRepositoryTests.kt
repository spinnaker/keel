package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.cleanupDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.time.Clock

internal class SqlFeatureRolloutRepositoryTests {
  private val jooq = testDatabase.context
  private val sqlRetry = RetryProperties(1, 0).let { SqlRetry(SqlRetryProperties(it, it)) }
  private val subject = SqlFeatureRolloutRepository(jooq, sqlRetry, Clock.systemDefaultZone())

  private val feature = "commencement-of-eschaton"
  private val resourceId = "titus:cluster:prod:fnord-main"

  @AfterEach
  fun flush() {
    cleanupDb(jooq)
  }

  @Test
  fun `if a feature has never been rolled out to a resource the count is zero`() {
    subject.countRolloutAttempts(feature, resourceId)
      .also { result ->
        expectThat(result) isEqualTo 0
      }
  }

  @Test
  fun `if a feature has been rolled out once to a resource the count is one`() {
    with(subject) {
      markRolloutStarted(feature, resourceId)
      countRolloutAttempts(feature, resourceId)
        .also { result ->
          expectThat(result) isEqualTo 1
        }
    }
  }

  @Test
  fun `multiple rollout attempts increment the count`() {
    val n = 5
    with(subject) {
      repeat(n) {
        markRolloutStarted(feature, resourceId)
      }
      countRolloutAttempts(feature, resourceId)
        .also { result ->
          expectThat(result) isEqualTo n
        }
    }
  }

  @Test
  fun `does not mix the counts for different features`() {
    with(subject) {
      markRolloutStarted(feature, resourceId)
      countRolloutAttempts("a-different-feature", resourceId)
        .also { result ->
          expectThat(result) isEqualTo 0
        }
    }
  }

  @Test
  fun `does not mix the counts for different resources`() {
    with(subject) {
      markRolloutStarted(feature, resourceId)
      countRolloutAttempts(feature, "titus:cluster:test:fnord-test")
        .also { result ->
          expectThat(result) isEqualTo 0
        }
    }
  }
}
