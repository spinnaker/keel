package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.persistence.DummyResourceSpecIdentifier
import com.netflix.spinnaker.keel.persistence.ResourceRepositoryTests
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.cleanupDb
import io.mockk.mockk
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock

internal class SqlResourceRepositoryTests : ResourceRepositoryTests<SqlResourceRepository>() {
  private val jooq = testDatabase.context
  private val retryProperties = RetryProperties(5, 100)
  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))
  private val deliveryConfigRepository = SqlDeliveryConfigRepository(
    jooq,
    clock,
    DummyResourceSpecIdentifier,
    configuredObjectMapper(),
    sqlRetry,
    publisher = mockk(relaxed = true)
  )

  override fun factory(clock: Clock, publisher: ApplicationEventPublisher): SqlResourceRepository {
    return SqlResourceRepository(
      jooq,
      clock,
      DummyResourceSpecIdentifier,
      emptyList(),
      configuredObjectMapper(),
      sqlRetry,
      publisher
    )
  }

  override val storeDeliveryConfig: (DeliveryConfig) -> Unit = deliveryConfigRepository::store

  override fun flush() {
    cleanupDb(jooq)
  }
}
