package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.artifacts.ArtifactOriginFilter
import com.netflix.spinnaker.keel.api.artifacts.BranchFilter
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.Commit
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.artifacts.Repo
import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.persistence.ArtifactRepositoryPromotionFlowTests
import com.netflix.spinnaker.keel.persistence.ArtifactRepositoryTests
import com.netflix.spinnaker.keel.persistence.DummyResourceSpecIdentifier
import com.netflix.spinnaker.keel.test.configuredTestObjectMapper
import com.netflix.spinnaker.keel.test.defaultArtifactSuppliers
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.cleanupDb
import com.netflix.spinnaker.time.MutableClock
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import java.time.Clock

class SqlArtifactRepositoryPromotionFlowTests : ArtifactRepositoryPromotionFlowTests<SqlArtifactRepository>() {
  private val jooq = testDatabase.context
  private val objectMapper = configuredTestObjectMapper()
  private val retryProperties = RetryProperties(1, 0)
  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))

  private val deliveryConfigRepository = SqlDeliveryConfigRepository(
    jooq,
    Clock.systemUTC(),
    DummyResourceSpecIdentifier,
    objectMapper,
    sqlRetry,
    defaultArtifactSuppliers(),
    publisher = mockk(relaxed = true)
  )

  override fun factory(clock: Clock, publisher: ApplicationEventPublisher): SqlArtifactRepository =
    SqlArtifactRepository(jooq, clock, objectMapper, sqlRetry, defaultArtifactSuppliers(), publisher)

  override fun SqlArtifactRepository.flush() {
    cleanupDb(jooq)
  }

  override fun persist(manifest: DeliveryConfig) {
    deliveryConfigRepository.store(manifest)
  }


  @AfterEach
  fun flush() {
    cleanupDb(jooq)
  }
}
