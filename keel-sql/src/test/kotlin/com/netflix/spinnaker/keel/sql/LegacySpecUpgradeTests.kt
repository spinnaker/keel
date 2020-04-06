package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.plugins.UnsupportedKind
import com.netflix.spinnaker.keel.resources.ResourceTypeIdentifier
import com.netflix.spinnaker.keel.resources.SpecMigrator
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.keel.test.resource
import com.netflix.spinnaker.kork.sql.config.RetryProperties
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import java.time.Clock.systemUTC
import java.time.Instant
import java.time.Instant.EPOCH
import strikt.api.expectCatching
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.succeeded

internal class LegacySpecUpgradeTests : JUnit5Minutests {

  data class AncientSpec(
    val name: String
  ) : ResourceSpec {
    override val id = name
    override val application = "fnord"
  }

  data class OldSpec(
    val name: String,
    val number: Int
  ) : ResourceSpec {
    override val id = name
    override val application = "fnord"
  }

  data class NewSpec(
    val name: String,
    val number: Int,
    val timestamp: Instant
  ) : ResourceSpec {
    override val id = name
    override val application = "fnord"
  }

  object Fixture {
    @JvmStatic
    val testDatabase = initTestDatabase()

    private val jooq = testDatabase.context
    private val retryProperties = RetryProperties(1, 0)
    private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))

    val ancientKind = ResourceKind.parseKind("test/whatever@v1")
    val oldKind = ResourceKind.parseKind("test/whatever@v2")
    val newKind = ResourceKind.parseKind("test/whatever@v3")

    val resourceTypeIdentifier = object : ResourceTypeIdentifier {
      override fun identify(kind: ResourceKind): Class<out ResourceSpec> {
        return if (kind == newKind) NewSpec::class.java
        else throw UnsupportedKind(kind)
      }
    }

    val v1to2Migrator = object : SpecMigrator {
      override val supportedKind = ancientKind

      override fun migrate(spec: Map<String, Any?>): Pair<ResourceKind, Map<String, Any?>> =
        oldKind to mapOf(
          "name" to spec["name"],
          "number" to 1
        )
    }
    val v2to3Migrator = object : SpecMigrator {
      override val supportedKind = oldKind

      override fun migrate(spec: Map<String, Any?>): Pair<ResourceKind, Map<String, Any?>> =
        newKind to mapOf(
          "name" to spec["name"],
          "number" to spec["number"],
          "timestamp" to EPOCH
        )
    }

    val repository = SqlResourceRepository(
      jooq,
      systemUTC(),
      resourceTypeIdentifier,
      listOf(v1to2Migrator, v2to3Migrator),
      configuredObjectMapper(),
      sqlRetry
    )

    val ancientResource = resource(ancientKind, AncientSpec("whatever"))
    val oldResource = resource(oldKind, OldSpec("whatever", 2))
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture }

    context("a spec with the old kind exists in the database") {
      before {
        repository.store(oldResource)
      }

      context("retrieving the resource") {
        test("the spec is converted to the new kind before being returned") {
          expectCatching { repository.get(oldResource.id) }
            .succeeded()
            .isA<Resource<NewSpec>>()
            .and {
              get { spec.name }.isEqualTo(oldResource.spec.name)
              get { spec.number }.isEqualTo(oldResource.spec.number)
              get { spec.timestamp }.isEqualTo(EPOCH)
            }
        }
      }
    }

    context("a spec with an even older kind exists in the database") {
      before {
        repository.store(ancientResource)
      }

      context("retrieving the resource") {
        test("the spec is converted to the newest kind before being returned") {
          expectCatching { repository.get(oldResource.id) }
            .succeeded()
            .isA<Resource<NewSpec>>()
            .and {
              get { spec.name }.isEqualTo(ancientResource.spec.name)
              get { spec.number }.isEqualTo(1)
              get { spec.timestamp }.isEqualTo(EPOCH)
            }
        }
      }
    }

    afterAll {
      Fixture.testDatabase.dataSource.close()
    }
  }
}
