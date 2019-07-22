package com.netflix.spinnaker.keel.tx

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.KeelApplication
import com.netflix.spinnaker.keel.actuation.ResourcePersister
import com.netflix.spinnaker.keel.api.ApiVersion
import com.netflix.spinnaker.keel.api.ArtifactType.DEB
import com.netflix.spinnaker.keel.api.DeliveryArtifact
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.ResourceName
import com.netflix.spinnaker.keel.api.SPINNAKER_API_V1
import com.netflix.spinnaker.keel.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.api.SubmittedEnvironment
import com.netflix.spinnaker.keel.api.SubmittedMetadata
import com.netflix.spinnaker.keel.api.SubmittedResource
import com.netflix.spinnaker.keel.api.name
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.NoSuchDeliveryConfigName
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.plugin.ResourceHandler
import com.netflix.spinnaker.keel.plugin.ResourceNormalizer
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.cleanupDb
import com.ninjasquad.springmockk.SpykBean
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.clearAllMocks
import io.mockk.every
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.junit.jupiter.SpringExtension
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.failed
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isFalse

@ExtendWith(SpringExtension::class)
@SpringBootTest(
  classes = [KeelApplication::class, TestConfiguration::class],
  webEnvironment = MOCK,
  properties = [
    "sql.enabled=true",
    "sql.connection-pools.default.jdbc-url=jdbc:tc:mysql:5.7.22://somehostname:someport/databasename",
    "sql.migration.jdbc-url=jdbc:tc:mysql:5.7.22://somehostname:someport/databasename",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "eureka.default-to-up=false"
  ]
)
internal class DeliveryConfigTransactionTests : JUnit5Minutests {

  @SpykBean
  lateinit var artifactRepository: ArtifactRepository

  @SpykBean
  lateinit var resourceRepository: ResourceRepository

  @SpykBean
  lateinit var deliveryConfigRepository: DeliveryConfigRepository

  @Autowired
  lateinit var resourcePersister: ResourcePersister

  @Autowired
  lateinit var jooq: DSLContext

  private fun ResourceRepository.allResourceNames(): List<ResourceName> =
    mutableListOf<ResourceName>()
      .also { list ->
        resourceRepository.allResources { list.add(it.name) }
      }

  object Fixture {
    val submittedManifest = SubmittedDeliveryConfig(
      name = "keel-manifest",
      application = "keel",
      artifacts = setOf(DeliveryArtifact(
        name = "keel",
        type = DEB
      )),
      environments = setOf(
        SubmittedEnvironment(
          name = "test",
          resources = setOf(SubmittedResource(
            apiVersion = SPINNAKER_API_V1.subApi("test"),
            kind = "whatever",
            metadata = SubmittedMetadata("keel@spinnaker"),
            spec = DummyResourceSpec("test", "resource in test")
          ))
        ),
        SubmittedEnvironment(
          name = "prod",
          resources = setOf(SubmittedResource(
            apiVersion = SPINNAKER_API_V1.subApi("test"),
            kind = "whatever",
            metadata = SubmittedMetadata("keel@spinnaker"),
            spec = DummyResourceSpec("prod", "resource in prod")
          ))
        )
      )
    )
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture }

    after {
      cleanupDb(jooq)
      clearAllMocks()
    }

    context("a resource attached to the delivery config fails to persist") {
      before {
        every {
          resourceRepository.store(match {
            it.name == ResourceName("test:whatever:prod")
          })
        } throws DataAccessException("o noes")

        expectCatching { resourcePersister.upsert(submittedManifest) }
          .failed()
          .isA<DataAccessException>()
      }

      test("the delivery config is not persisted") {
        expectCatching { deliveryConfigRepository.get("keel-manifest") }
          .failed()
          .isA<NoSuchDeliveryConfigName>()
      }

      test("the other resources are not persisted") {
        expectThat(resourceRepository.allResourceNames())
          .isEmpty()
      }

      test("the artifact not persisted") {
        expectThat(artifactRepository.isRegistered("keel", DEB))
          .isFalse()
      }
    }

    context("an artifact attached to the delivery config fails to persist") {
      before {
        every { artifactRepository.register(any()) } throws DataAccessException("o noes")

        expectCatching { resourcePersister.upsert(submittedManifest) }
          .failed()
          .isA<DataAccessException>()
      }

      test("the delivery config is not persisted") {
        expectCatching { deliveryConfigRepository.get("keel-manifest") }
          .failed()
          .isA<NoSuchDeliveryConfigName>()
      }

      test("the resources are not persisted") {
        expectThat(resourceRepository.allResourceNames())
          .isEmpty()
      }
    }

    context("the delivery config itself fails to persist") {
      before {
        every { deliveryConfigRepository.store(any()) } throws DataAccessException("o noes")

        expectCatching { resourcePersister.upsert(submittedManifest) }
          .failed()
          .isA<DataAccessException>()
      }

      test("the resources are not persisted") {
        expectThat(resourceRepository.allResourceNames())
          .isEmpty()
      }

      test("the artifact not persisted") {
        expectThat(artifactRepository.isRegistered("keel", DEB))
          .isFalse()
      }
    }
  }
}

@Configuration
internal class TestConfiguration {
  @Bean
  fun dummyResourceHandler() = DummyResourceHandler
}

internal object DummyResourceHandler : ResourceHandler<DummyResourceSpec> {
  override val apiVersion: ApiVersion = SPINNAKER_API_V1.subApi("test")

  override val supportedKind: Pair<ResourceKind, Class<DummyResourceSpec>> =
    ResourceKind("test", "whatever", "whatevers") to DummyResourceSpec::class.java

  override val objectMapper: ObjectMapper = configuredObjectMapper()

  override val normalizers: List<ResourceNormalizer<*>> = emptyList()

  override fun generateName(spec: DummyResourceSpec): ResourceName =
    ResourceName("test:whatever:${spec.state}")

  override suspend fun current(resource: Resource<DummyResourceSpec>): DummyResourceSpec? {
    TODO("not implemented")
  }

  override suspend fun delete(resource: Resource<DummyResourceSpec>) {
    TODO("not implemented")
  }

  override val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }
}

internal data class DummyResourceSpec(
  val state: String,
  val data: String = "some data"
)
