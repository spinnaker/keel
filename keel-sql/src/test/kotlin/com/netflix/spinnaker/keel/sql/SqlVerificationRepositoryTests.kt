package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.verification.VerificationContext
import com.netflix.spinnaker.keel.artifacts.DockerArtifactSupplier
import com.netflix.spinnaker.keel.persistence.VerificationRepositoryTests
import com.netflix.spinnaker.keel.resources.ResourceSpecIdentifier
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import java.time.Clock

internal class SqlVerificationRepositoryTests :
  VerificationRepositoryTests<SqlVerificationRepository>() {

  private val jooq = testDatabase.context
  private val retryProperties = RetryProperties(1, 0)
  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))
  private val artifactSuppliers = listOf(DockerArtifactSupplier(mockk(), mockk(), mockk()))

  private val deliveryConfigRepository = SqlDeliveryConfigRepository(
    jooq,
    Clock.systemUTC(),
    ResourceSpecIdentifier(),
    configuredObjectMapper(),
    sqlRetry,
    artifactSuppliers
  )
  private val artifactRepository =
    SqlArtifactRepository(jooq, Clock.systemUTC(), configuredObjectMapper(), sqlRetry, artifactSuppliers)

  override fun createSubject() =
    SqlVerificationRepository(jooq, Clock.systemUTC(), mockk(), configuredObjectMapper(), sqlRetry, artifactSuppliers)

  override fun VerificationContext.setup() {
    artifactRepository.register(artifact)
    deliveryConfigRepository.store(deliveryConfig)
    artifactRepository.storeArtifactVersion(
      PublishedArtifact(
        artifact.name,
        artifact.type,
        version
      )
    )
  }

  override fun VerificationContext.setupCurrentArtifactVersion() {
    artifactRepository.markAsSuccessfullyDeployedTo(
      deliveryConfig,
      artifact,
      version,
      environmentName
    )
  }

  @AfterEach
  fun flush() {
    SqlTestUtil.cleanupDb(jooq)
  }

  companion object {
    private val testDatabase = initTestDatabase()

    @JvmStatic
    @AfterAll
    fun shutdown() {
      testDatabase.dataSource.close()
    }
  }
}
