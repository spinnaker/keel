package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.TaskTrackingRepositoryTests
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil
import org.junit.jupiter.api.AfterAll


private val testDatabase = initTestDatabase()
private val jooq = testDatabase.context


internal object SqlTaskTrackingRepositoryTests : TaskTrackingRepositoryTests<SqlTaskTrackingRepository>() {

  override fun factory(): SqlTaskTrackingRepository {
    return SqlTaskTrackingRepository(
      jooq
    )
  }

  override fun SqlTaskTrackingRepository.flush() {
    SqlTestUtil.cleanupDb(jooq)
  }

  @JvmStatic
  @AfterAll
  fun shutdown() {
    testDatabase.dataSource.close()
  }
}
