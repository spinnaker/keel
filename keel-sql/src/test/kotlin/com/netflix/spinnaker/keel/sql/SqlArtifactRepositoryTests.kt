package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.ArtifactStatus.SNAPSHOT
import com.netflix.spinnaker.keel.persistence.ArtifactRepositoryTests
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.cleanupDb
import java.time.Clock

class SqlArtifactRepositoryTests : ArtifactRepositoryTests<SqlArtifactRepository>() {
  private val testDatabase = initTestDatabase()
  private val jooq = testDatabase.context
  private val objectmapper = configuredObjectMapper()

  private val deliveryConfigRepository = SqlDeliveryConfigRepository(
    jooq,
    Clock.systemDefaultZone(),
    DummyResourceTypeIdentifier
  )

  override fun factory(clock: Clock): SqlArtifactRepository =
    SqlArtifactRepository(jooq, clock, objectmapper)

  override fun SqlArtifactRepository.flush() {
    cleanupDb(jooq)
  }

  override fun Fixture<SqlArtifactRepository>.persist() {
    with(subject) {
      register(artifact1)
      setOf(version1, version2, version3).forEach {
        store(artifact1, it, SNAPSHOT)
      }
      register(artifact2)
      setOf(version1, version2, version3).forEach {
        store(artifact2, it, SNAPSHOT)
      }
    }
    deliveryConfigRepository.store(manifest)
  }
}
