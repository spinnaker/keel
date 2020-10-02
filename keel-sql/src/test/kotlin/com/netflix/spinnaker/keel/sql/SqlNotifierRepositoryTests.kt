package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.NotifierRepositoryTests
import com.netflix.spinnaker.keel.persistence.UnhealthyVetoRepositoryTests
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil
import org.junit.jupiter.api.AfterAll
import java.time.Clock

internal object SqlNotifierRepositoryTests : NotifierRepositoryTests<SqlNotifierRepository>() {

  private val testDatabase = initTestDatabase()
  private val jooq = testDatabase.context
  private val retryProperties = RetryProperties(1, 0)
  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))

  override fun factory(clock: Clock): SqlNotifierRepository {
    return SqlNotifierRepository(
      clock,
      jooq,
      sqlRetry
    )
  }

  override fun SqlNotifierRepository.flush() {
    SqlTestUtil.cleanupDb(jooq)
  }

  @JvmStatic
  @AfterAll
  fun shutdown() {
    testDatabase.dataSource.close()
  }
}