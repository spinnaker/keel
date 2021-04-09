package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.NotificationFrequency.normal
import com.netflix.spinnaker.keel.api.NotificationType.slack
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.plugins.kind
import com.netflix.spinnaker.keel.core.api.ManualJudgementConstraint
import com.netflix.spinnaker.keel.persistence.CombinedRepository
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT_RESOURCE
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ENVIRONMENT_VERSION
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.LATEST_ENVIRONMENT
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.RESOURCE
import com.netflix.spinnaker.keel.resources.ResourceSpecIdentifier
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import com.netflix.spinnaker.keel.test.configuredTestObjectMapper
import com.netflix.spinnaker.keel.test.defaultArtifactSuppliers
import com.netflix.spinnaker.keel.test.deliveryConfig
import com.netflix.spinnaker.keel.test.randomString
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.cleanupDb
import org.jooq.Table
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import strikt.api.Assertion
import strikt.api.expect
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.first
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import strikt.assertions.isSuccess
import java.time.Clock.systemUTC
import kotlin.math.exp
import java.time.Clock
import java.time.Duration

class EnvironmentVersioningTests {
  private val jooq = testDatabase.context
  private val objectMapper = configuredTestObjectMapper()
  private val retryProperties = RetryProperties(1, 0)
  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))
  private val resourceSpecIdentifier =
    ResourceSpecIdentifier(
      kind<DummyResourceSpec>("test/whatever@v1")
    )
  val deliveryConfig = deliveryConfig()

  private val deliveryConfigRepository = SqlDeliveryConfigRepository(
    jooq,
    systemUTC(),
    resourceSpecIdentifier,
    objectMapper,
    sqlRetry,
    defaultArtifactSuppliers()
  )

  private val artifactRepository = SqlArtifactRepository(
    jooq,
    systemUTC(),
    objectMapper,
    sqlRetry
  )

  private val resourceRepository = SqlResourceRepository(
    jooq,
    systemUTC(),
    resourceSpecIdentifier,
    emptyList(),
    objectMapper,
    sqlRetry
  )

  private val verificationRepository = SqlVerificationRepository(
    jooq = jooq,
    clock = systemUTC(),
    resourceSpecIdentifier = resourceSpecIdentifier,
    objectMapper = objectMapper,
    sqlRetry = sqlRetry,
    environment = MockEnvironment()
  )

  private val repository = CombinedRepository(
    deliveryConfigRepository,
    artifactRepository,
    resourceRepository,
    verificationRepository,
    systemUTC(),
    { },
    objectMapper
  )

  @BeforeEach
  fun storeDeliveryConfig() {
    repository.upsertDeliveryConfig(deliveryConfig)
  }

  @AfterEach
  fun flush() {
    cleanupDb(jooq)
  }

  @Test
  fun `storing an environment with no changes does not create a new version`() {
    val initialVersion = latestVersion()
    expectThat(initialVersion).describedAs("initial version") isEqualTo 1

    repository.upsertDeliveryConfig(deliveryConfig)

    val updatedVersion = latestVersion()
    expectThat(updatedVersion).describedAs("updated version") isEqualTo initialVersion
  }

  @Test
  fun `storing an environment with updated constraints, verifications, or notifications does not create a new version`() {
    val initialVersion = latestVersion()
    expectThat(initialVersion).describedAs("initial version") isEqualTo 1

    deliveryConfig.run {
      copy(
        environments = environments.first().run {
          copy(
            constraints = setOf(ManualJudgementConstraint()),
            verifyWith = listOf(DummyVerification()),
            notifications = setOf(NotificationConfig(type = slack,
              address = "#trashpandas",
              frequency = normal))
          )
        }
          .let(::setOf)
      )
    }
    repository.upsertDeliveryConfig(deliveryConfig)

    val updatedVersion = latestVersion()
    expectThat(updatedVersion).describedAs("updated version") isEqualTo initialVersion
  }

  @Test
  fun `storing an environment with an updated resource creates a new version`() {
    val initialVersion = latestVersion()
    expectThat(initialVersion).describedAs("initial version") isEqualTo 1

    deliveryConfig.withUpdatedResource()
      .also(repository::upsertDeliveryConfig)

    val updatedVersion = latestVersion()
    expectThat(updatedVersion).describedAs("updated version") isEqualTo initialVersion + 1
  }

  @Test
  fun `after creating a new environment version it's still possible to get the environment for a resource`() {
    deliveryConfig.withUpdatedResource()
      .also(repository::upsertDeliveryConfig)

    expectCatching {
      repository.environmentFor(deliveryConfig.resources.first().id)
    }
      .isSuccess()
      .and {
        get(Environment::name) isEqualTo deliveryConfig.environments.first().name
        get(Environment::resources) hasSize deliveryConfig.environments.first().resources.size
      }
  }

  @Test
  fun `after creating a new environment version it's still possible to get the delivery config for a resource`() {
    deliveryConfig.withUpdatedResource()
      .also(repository::upsertDeliveryConfig)

    expectCatching {
      repository.deliveryConfigFor(deliveryConfig.resources.first().id)
    }
      .isSuccess()
      .get(DeliveryConfig::name) isEqualTo deliveryConfig.name
  }

  @Test
  fun `after creating a new environment version only the newest resource version gets checked`() {
    deliveryConfig.withUpdatedResource()
      .also(repository::upsertDeliveryConfig)

    expectCatching {
      repository.resourcesDueForCheck(Duration.ofMinutes(1), 1)
    }
      .isSuccess()
      .hasSize(1)
      .first()
      .get { metadata["version"] } isEqualTo 2

    expectCatching {
      repository.resourcesDueForCheck(Duration.ofMinutes(1), 1)
    }
      .isSuccess()
      .isEmpty()
  }

  @Test
  fun `after creating a new environment version only the newest environment version gets checked`() {
    deliveryConfig.withUpdatedResource()
      .also(repository::upsertDeliveryConfig)

    expectCatching {
      repository.deliveryConfigsDueForCheck(Duration.ofMinutes(1), 1)
    }
      .isSuccess()
      .hasSize(1)
      .first()
      .get(DeliveryConfig::environments)
      .hasSize(1)
      .first()
      .get(Environment::resources)
      .hasSize(1)
      .first()
      .get { metadata["version"] } isEqualTo 2

    expectCatching {
      repository.deliveryConfigsDueForCheck(Duration.ofMinutes(1), 1)
    }
      .isSuccess()
      .isEmpty()
  }

  @Test
  fun `removing a resource from an environment creates a new version without that resource`() {
    val initialVersion = latestVersion()
    expectThat(initialVersion).describedAs("initial version") isEqualTo 1

    deliveryConfig.withEmptyEnvironment()
      .also(repository::upsertDeliveryConfig)

    val updatedVersion = latestVersion()
    expectThat(updatedVersion).describedAs("updated version") isEqualTo initialVersion + 1

    expectCatching {
      repository.getDeliveryConfig(deliveryConfig.name)
    }
      .isSuccess()
      .get(DeliveryConfig::resources)
      .isEmpty()
  }

  @Test
  fun `removing a resource from an environment does not delete the resource and it remains attached to the old environment version`() {
    deliveryConfig.withEmptyEnvironment()
      .also(repository::upsertDeliveryConfig)

    expect {
      that(ENVIRONMENT).count() isEqualTo 1          // environment still exists
      that(RESOURCE).count() isEqualTo 1             // resource still exists
      that(ENVIRONMENT_VERSION).count() isEqualTo 2  // there are 2 versions of the environment
      that(ENVIRONMENT_RESOURCE).count() isEqualTo 1 // only one environment version links to a resource
    }
  }

  @Test
  fun `removing an entire environment deletes all versions of it and all resources`() {
    deliveryConfig.withNoEnvironments()
      .also(repository::upsertDeliveryConfig)

    expect {
      that(ENVIRONMENT).isEmpty()
      that(RESOURCE).isEmpty()
      that(ENVIRONMENT_VERSION).isEmpty()
      that(ENVIRONMENT_RESOURCE).isEmpty()
    }
  }

  private fun DeliveryConfig.withUpdatedResource() =
    copy(
      environments = environments.first().run {
        copy(resources = resources.first().run {
          copy(spec = (spec as DummyResourceSpec).run {
            copy(data = randomString())
          })
        }
          .let(::setOf)
        )
      }
        .let(::setOf)
    )

  private fun DeliveryConfig.withEmptyEnvironment() =
    copy(
      environments = environments
        .first()
        .run { copy(resources = emptySet()) }
        .let(::setOf)
    )

  private fun DeliveryConfig.withNoEnvironments() =
    copy(environments = emptySet())

  private fun latestVersion() =
    jooq
      .select(LATEST_ENVIRONMENT.VERSION)
      .from(LATEST_ENVIRONMENT)
      .fetchOne(LATEST_ENVIRONMENT.VERSION)

  private fun Table<*>.count() =
    jooq.selectCount().from(this).fetchOneInto<Int>()

  private fun <T : Table<*>> Assertion.Builder<T>.count() =
    get("row count") { count() }

  private fun <T : Table<*>> Assertion.Builder<T>.isEmpty() =
    assert("has no rows") { subject ->
      when(val rowCount = subject.count()) {
        0 -> pass("found 0 rows")
        1 -> fail("found 1 row")
        else -> fail("found $rowCount rows")
      }
    }

  data class DummyVerification(
    override val id: String = "whatever",
  ) : Verification {
    override val type = "verification"
  }
}
