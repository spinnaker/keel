package com.netflix.spinnaker.keel.tx

import com.netflix.spinnaker.keel.KeelApplication
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceKind.Companion.parseKind
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.artifacts.DEBIAN
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.SubmittedEnvironment
import com.netflix.spinnaker.keel.core.api.SubmittedResource
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.NoSuchDeliveryConfigName
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.test.DummyResourceHandlerV1
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.cleanupDb
import com.ninjasquad.springmockk.SpykBean
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.clearAllMocks
import io.mockk.every
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isFailure
import strikt.assertions.isFalse

@SpringBootTest(
  classes = [KeelApplication::class, TestConfiguration::class],
  webEnvironment = MOCK
)
internal class DeliveryConfigTransactionTests
@Autowired constructor(
  val repository: KeelRepository,
  val jooq: DSLContext
) : JUnit5Minutests {

  @SpykBean
  lateinit var artifactRepository: ArtifactRepository

  @SpykBean
  lateinit var resourceRepository: ResourceRepository

  @SpykBean
  lateinit var deliveryConfigRepository: DeliveryConfigRepository

  private fun KeelRepository.allResourceNames(): List<String> =
    mutableListOf<String>()
      .also { list ->
        allResources { list.add(it.id) }
      }

  object Fixture {
    val submittedManifest = SubmittedDeliveryConfig(
      name = "keel-manifest",
      application = "keel",
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(
        DebianArtifact(
          name = "keel",
          deliveryConfigName = "keel-manifest",
          vmOptions = VirtualMachineOptions(baseOs = "bionic", regions = setOf("us-west-2"))
        )
      ),
      environments = setOf(
        SubmittedEnvironment(
          name = "test",
          resources = setOf(
            SubmittedResource(
              kind = parseKind("test/whatever@v1"),
              metadata = mapOf("serviceAccount" to "keel@spinnaker"),
              spec = DummyResourceSpec("test", "resource in test", "keel")
            )
          )
        ),
        SubmittedEnvironment(
          name = "prod",
          resources = setOf(
            SubmittedResource(
              kind = parseKind("test/whatever@v1"),
              metadata = mapOf("serviceAccount" to "keel@spinnaker"),
              spec = DummyResourceSpec("prod", "resource in prod", "keel")
            )
          )
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
          resourceRepository.store<ResourceSpec>(
            match {
              it.id == "test:whatever:prod"
            }
          )
        } throws DataAccessException("o noes")

        expectCatching { repository.upsertDeliveryConfig(submittedManifest) }
          .isFailure()
          .isA<DataAccessException>()
      }

      test("the delivery config is not persisted") {
        expectCatching { repository.getDeliveryConfig("keel-manifest") }
          .isFailure()
          .isA<NoSuchDeliveryConfigName>()
      }

      test("the other resources are not persisted") {
        expectThat(repository.allResourceNames())
          .isEmpty()
      }

      test("the artifact is not persisted") {
        expectThat(repository.isRegistered("keel", DEBIAN))
          .isFalse()
      }
    }

    context("an artifact attached to the delivery config fails to persist") {
      before {
        every { artifactRepository.register(any()) } throws DataAccessException("o noes")

        expectCatching { repository.upsertDeliveryConfig(submittedManifest) }
          .isFailure()
          .isA<DataAccessException>()
      }

      test("the delivery config is not persisted") {
        expectCatching { repository.getDeliveryConfig("keel-manifest") }
          .isFailure()
          .isA<NoSuchDeliveryConfigName>()
      }

      test("the resources are not persisted") {
        expectThat(repository.allResourceNames())
          .isEmpty()
      }
    }

    context("the delivery config itself fails to persist") {
      before {
        every { deliveryConfigRepository.store(any()) } throws DataAccessException("o noes")

        expectCatching { repository.upsertDeliveryConfig(submittedManifest) }
          .isFailure()
          .isA<DataAccessException>()
      }

      test("the resources are not persisted") {
        expectThat(repository.allResourceNames())
          .isEmpty()
      }

      test("the artifact not persisted") {
        expectThat(repository.isRegistered("keel", DEBIAN))
          .isFalse()
      }
    }
  }
}

@Configuration
internal class TestConfiguration {
  @Bean
  fun dummyResourceHandler() =
    DummyResourceHandlerV1
}
