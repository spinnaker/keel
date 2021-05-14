package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.persistence.DummyResourceSpecIdentifier
import com.netflix.spinnaker.keel.persistence.UnhealthyRepositoryTests
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil
import io.mockk.mockk
import java.time.Clock

internal object SqlUnhealthyRepositoryTests : UnhealthyRepositoryTests<SqlUnhealthyRepository>() {
  private val jooq = testDatabase.context
  private val retryProperties = RetryProperties(1, 0)
  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))
  private val resourceRepository = SqlResourceRepository(
    jooq,
    clock,
    DummyResourceSpecIdentifier,
    emptyList(),
    configuredObjectMapper(),
    sqlRetry,
    publisher = mockk(relaxed = true)
  )

  override fun factory(clock: Clock): SqlUnhealthyRepository =
    SqlUnhealthyRepository(
      clock,
      jooq,
      sqlRetry
    )

  override fun store(resource: Resource<*>) {
    resourceRepository.store(resource)
  }

  override fun flush() {
    SqlTestUtil.cleanupDb(jooq)
  }
}
