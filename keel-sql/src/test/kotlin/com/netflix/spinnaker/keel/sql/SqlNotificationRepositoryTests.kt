package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.NotificationRepositoryTests
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil
import java.time.Clock

internal class SqlNotificationRepositoryTests : NotificationRepositoryTests<SqlNotificationRepository>() {

  private val jooq = testDatabase.context
  private val retryProperties = RetryProperties(1, 0)
  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))

  override fun factory(clock: Clock): SqlNotificationRepository {
    return SqlNotificationRepository(
      clock,
      jooq,
      sqlRetry
    )
  }

  override fun SqlNotificationRepository.flush() {
    SqlTestUtil.cleanupDb(jooq)
  }
}
