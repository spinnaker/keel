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
import com.netflix.spinnaker.keel.persistence.ArtifactRepositoryTests
import com.netflix.spinnaker.keel.persistence.DummyResourceSpecIdentifier
import com.netflix.spinnaker.keel.test.configuredTestObjectMapper
import com.netflix.spinnaker.keel.test.defaultArtifactSuppliers
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.cleanupDb
import com.netflix.spinnaker.time.MutableClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import java.time.Clock

class SqlArtifactRepositoryTests : ArtifactRepositoryTests<SqlArtifactRepository>() {
  private val jooq = testDatabase.context
  private val objectMapper = configuredTestObjectMapper()
  private val retryProperties = RetryProperties(1, 0)
  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))
  private val mutableClock = MutableClock()
  private val artifact = DockerArtifact(name = "myart", deliveryConfigName = "myconfig", from = ArtifactOriginFilter(branch = BranchFilter("main")))

  private val deliveryConfigRepository = SqlDeliveryConfigRepository(
    jooq,
    Clock.systemUTC(),
    DummyResourceSpecIdentifier,
    objectMapper,
    sqlRetry,
    defaultArtifactSuppliers()
  )

  override fun factory(clock: Clock): SqlArtifactRepository =
    SqlArtifactRepository(jooq, clock, objectMapper, sqlRetry, defaultArtifactSuppliers())

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

  @Test
  fun `can remove newer pending versions`() {
    val version0 = "fnord-1.0.0-h0.a0a0a0a"
    val version1 = "fnord-1.0.1-h1.b1b1b1b"
    val version2 = "fnord-1.0.2-h2.c2c2c2c"
    val version3 = "fnord-1.0.3-h3.d3d3d3d"
    val version4 = "fnord-1.0.4-h4.e4e4e4e"

    val versions = listOf(version0, version1, version2, version3, version4).toArtifactVersions(artifact)

    val pending = factory(mutableClock).removeExtra(versions, artifact, version2)
    expectThat(pending.size).isEqualTo(3)
    expectThat(pending.map { it.version }).containsExactlyInAnyOrder(version0, version1, version2)
  }

  private fun Collection<String>.toArtifactVersions(artifact: DeliveryArtifact) =
    map { version ->
      PublishedArtifact(
        name = artifact.name,
        type = artifact.type,
        reference = artifact.reference,
        version = version,
        gitMetadata = null,
        buildMetadata = null,
        createdAt = mutableClock.tickMinutes(1)
      ) }

}
