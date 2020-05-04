package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.BRANCH_JOB_COMMIT_BY_JOB
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.SubmittedEnvironment
import com.netflix.spinnaker.keel.core.api.SubmittedResource
import com.netflix.spinnaker.keel.core.api.normalize
import com.netflix.spinnaker.keel.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.events.ResourceCreated
import com.netflix.spinnaker.keel.events.ResourceUpdated
import com.netflix.spinnaker.keel.exceptions.DuplicateArtifactReferenceException
import com.netflix.spinnaker.keel.exceptions.DuplicateResourceIdException
import com.netflix.spinnaker.keel.resources.ResourceSpecIdentifier
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import com.netflix.spinnaker.keel.test.TEST_API_V1
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import org.springframework.context.ApplicationEventPublisher
import strikt.api.expect
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.failed
import strikt.assertions.first
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue
import strikt.assertions.succeeded

/**
 * Tests that involve creating, updating, or deleting things from two or more of the three repositories present.
 *
 * Tests that only apply to one repository should live in the repository-specific test classes.
 */
abstract class CombinedRepositoryTests<D : DeliveryConfigRepository, R : ResourceRepository, A : ArtifactRepository> :
  JUnit5Minutests {

  abstract fun createDeliveryConfigRepository(resourceSpecIdentifier: ResourceSpecIdentifier): D
  abstract fun createResourceRepository(resourceSpecIdentifier: ResourceSpecIdentifier): R
  abstract fun createArtifactRepository(): A

  open fun flush() {}

  val configName = "my-config"

  val artifact = DockerArtifact(name = "org/image", deliveryConfigName = configName)
  val newArtifact = artifact.copy(reference = "myart")
  val firstResource = resource()
  val secondResource = resource()
  val firstEnv = Environment(name = "env1", resources = setOf(firstResource))
  val secondEnv = Environment(name = "env2", resources = setOf(secondResource))
  val deliveryConfig = DeliveryConfig(
    name = configName,
    application = "fnord",
    serviceAccount = "keel@spinnaker",
    artifacts = setOf(artifact),
    environments = setOf(firstEnv)
  )

  data class Fixture<D : DeliveryConfigRepository, R : ResourceRepository, A : ArtifactRepository>(
    val deliveryConfigRepositoryProvider: (ResourceSpecIdentifier) -> D,
    val resourceRepositoryProvider: (ResourceSpecIdentifier) -> R,
    val artifactRepositoryProvider: () -> A
  ) {
    private val clock: Clock = Clock.systemUTC()
    val publisher: ApplicationEventPublisher = mockk(relaxUnitFun = true)

    internal val deliveryConfigRepository: D = deliveryConfigRepositoryProvider(DummyResourceSpecIdentifier)
    internal val resourceRepository: R = resourceRepositoryProvider(DummyResourceSpecIdentifier)
    internal val artifactRepository: A = artifactRepositoryProvider()

    val subject = CombinedRepository(
      deliveryConfigRepository,
      artifactRepository,
      resourceRepository,
      clock,
      publisher
    )

    fun resourcesDueForCheck() =
      subject.resourcesDueForCheck(Duration.ofMinutes(1), Int.MAX_VALUE)

    fun CombinedRepository.allResourceNames(): List<String> =
      mutableListOf<String>()
        .also { list ->
          allResources { list.add(it.id) }
        }
  }

  fun tests() = rootContext<Fixture<D, R, A>> {
    fixture {
      Fixture(
        deliveryConfigRepositoryProvider = this@CombinedRepositoryTests::createDeliveryConfigRepository,
        resourceRepositoryProvider = this@CombinedRepositoryTests::createResourceRepository,
        artifactRepositoryProvider = this@CombinedRepositoryTests::createArtifactRepository
      )
    }

    after {
      flush()
    }

    context("creating and deleting delivery configs") {
      before {
        subject.upsertDeliveryConfig(deliveryConfig)
      }

      context("delivery config created") {
        test("delivery config is persisted") {
          expectCatching { subject.getDeliveryConfig(deliveryConfig.name) }
            .succeeded()
        }

        test("artifacts are persisted") {
          expectThat(subject.isRegistered("org/image", ArtifactType.docker)).isTrue()
          verify { publisher.publishEvent(ArtifactRegisteredEvent(DockerArtifact(name = "org/image", deliveryConfigName = configName))) }
        }

        test("individual resources are persisted") {
          deliveryConfig.resources.map { it.id }.forEach { id ->
            expectCatching {
              subject.getResource(id)
            }.succeeded()
          }
        }
      }

      context("delivery config was deleted") {
        before {
          subject.deleteDeliveryConfig(configName)
        }
        test("everything is deleted") {
          expectThrows<NoSuchDeliveryConfigException> { deliveryConfigRepository.get(configName) }
          expectThrows<NoSuchResourceException> { resourceRepository.get(firstResource.id) }
          expectThat(artifactRepository.get(artifact.name, artifact.type, configName)).isEmpty()
        }
      }

      context("delivery config is updated") {
        context("artifact and resource have changed") {
          before {
            val updatedConfig = deliveryConfig.copy(
              artifacts = setOf(newArtifact),
              environments = setOf(firstEnv.copy(resources = setOf(secondResource)))
            )
            subject.upsertDeliveryConfig(updatedConfig)
          }

          test("no longer present dependents are removed") {
            expectThrows<NoSuchResourceException> { resourceRepository.get(firstResource.id) }
            expectThrows<ArtifactNotFoundException> {
              artifactRepository.get(name = artifact.name, type = artifact.type, reference = "org/image", deliveryConfigName = configName)
            }
          }

          test("correct resources still exist") {
            expectCatching { resourceRepository.get(secondResource.id) }.succeeded()
            expectCatching {
              artifactRepository.get(name = newArtifact.name, type = newArtifact.type, reference = "myart", deliveryConfigName = configName)
            }.succeeded()
          }
        }

        context("artifact properties modified") {
          before {
            val updatedConfig = deliveryConfig.copy(
              artifacts = setOf(artifact.copy(tagVersionStrategy = BRANCH_JOB_COMMIT_BY_JOB))
            )
            subject.upsertDeliveryConfig(updatedConfig)
          }

          test("artifact is updated but still present") {
            expectCatching {
              artifactRepository.get(name = artifact.name, type = artifact.type, reference = artifact.reference, deliveryConfigName = configName)
            }.succeeded()
              .isA<DockerArtifact>()
              .get { tagVersionStrategy }.isEqualTo(BRANCH_JOB_COMMIT_BY_JOB)
          }
        }

        context("environment changed and artifact removed") {
          before {
            val updatedConfig = deliveryConfig.copy(
              artifacts = setOf(),
              environments = setOf(firstEnv.copy(name = "env2"))
            )
            subject.upsertDeliveryConfig(updatedConfig)
          }
          test("old environment is gone") {
            val config = deliveryConfigRepository.get(configName)
            expect {
              that(config.environments.size).isEqualTo(1)
              that(config.environments.first().name).isEqualTo("env2")
              that(config.resources.size).isEqualTo(1)
              that(config.resources.first().id).isEqualTo(firstResource.id)
              that(config.artifacts.size).isEqualTo(0)
              that(artifactRepository.get(artifact.name, artifact.type, configName)).isEmpty()
            }
          }
        }
      }
    }

    context("persisting individual resources") {
      context("resource lifecycle") {
        val resource = SubmittedResource(
          metadata = mapOf("serviceAccount" to "keel@spinnaker"),
          kind = TEST_API_V1.qualify("whatever"),
          spec = DummyResourceSpec(data = "o hai")
        ).normalize()

        context("creation") {
          before {
            subject.upsert(resource)
          }

          test("stores the resource") {
            val persistedResource = subject.getResource(resource.id)
            expectThat(persistedResource) {
              isA<Resource<DummyResourceSpec>>()
              get { id }.isEqualTo(resource.id)
              get { (spec as DummyResourceSpec).data }.isEqualTo("o hai")
            }
          }

          test("records that the resource was created") {
            verify {
              publisher.publishEvent(ofType<ResourceCreated>())
            }
          }

          test("will check the resource") {
            expectThat(resourcesDueForCheck())
              .hasSize(1)
              .first()
              .get { id }.isEqualTo(resource.id)
          }

          context("after an update") {
            before {
              resourcesDueForCheck()
              subject.upsert(resource.copy(spec = DummyResourceSpec(id = resource.spec.id, data = "kthxbye")))
            }

            test("stores the updated resource") {
              expectThat(subject.getResource(resource.id))
                .get { spec }
                .isA<DummyResourceSpec>()
                .get { data }
                .isEqualTo("kthxbye")
            }

            test("records that the resource was updated") {
              verify {
                publisher.publishEvent(ofType<ResourceUpdated>())
              }
            }

            test("will check the resource again") {
              expectThat(resourcesDueForCheck())
                .hasSize(1)
                .first()
                .get { id }.isEqualTo(resource.id)
            }
          }

          context("after a no-op update") {
            before {
              resourcesDueForCheck()
              subject.upsert(resource)
            }

            test("does not record that the resource was updated") {
              verify(exactly = 0) {
                publisher.publishEvent(ofType<ResourceUpdated>())
              }
            }

            test("will not check the resource again") {
              expectThat(resourcesDueForCheck())
                .isEmpty()
            }
          }
        }
      }
    }

    context("persisting delivery config manifests") {

      context("a delivery config with non-unique resource ids errors while persisting") {
        val submittedConfig = SubmittedDeliveryConfig(
          name = configName,
          application = "keel",
          serviceAccount = "keel@spinnaker",
          artifacts = setOf(artifact),
          environments = setOf(
            SubmittedEnvironment(
              name = "test",
              resources = setOf(
                SubmittedResource(
                  kind = TEST_API_V1.qualify("whatever"),
                  spec = DummyResourceSpec("test", "im a twin", "keel")
                )
              ),
              constraints = emptySet()
            ),
            SubmittedEnvironment(
              name = "prod",
              resources = setOf(
                SubmittedResource(
                  kind = TEST_API_V1.qualify("whatever"),
                  spec = DummyResourceSpec("test", "im a twin", "keel")
                )
              ),
              constraints = emptySet()
            )
          )
        )

        test("an error is thrown and config is deleted") {
          expectCatching {
            subject.upsertDeliveryConfig(submittedConfig)
          }.failed()
            .isA<DuplicateResourceIdException>()

          expectThat(subject.allResourceNames().size).isEqualTo(0)
        }
      }

      context("a delivery config with non-unique artifact references errors while persisting") {
        // Two different artifacts with the same reference
        val artifacts = setOf(
          DockerArtifact(name = "org/thing-1", deliveryConfigName = configName, reference = "thing"),
          DockerArtifact(name = "org/thing-2", deliveryConfigName = configName, reference = "thing")
        )

        val submittedConfig = SubmittedDeliveryConfig(
          name = configName,
          application = "keel",
          serviceAccount = "keel@spinnaker",
          artifacts = artifacts,
          environments = setOf(
            SubmittedEnvironment(
              name = "test",
              resources = setOf(
                SubmittedResource(
                  metadata = mapOf("serviceAccount" to "keel@spinnaker"),
                  kind = TEST_API_V1.qualify("whatever"),
                  spec = DummyResourceSpec(data = "o hai")
                )
              ),
              constraints = emptySet()
            )
          )
        )
        test("an error is thrown and config is deleted") {
          expectCatching {
            subject.upsertDeliveryConfig(submittedConfig)
          }.failed()
            .isA<DuplicateArtifactReferenceException>()

          expectThat(subject.allResourceNames().size).isEqualTo(0)
        }
      }

      context("a second delivery config for an app fails to persist") {
        val submittedConfig1 = SubmittedDeliveryConfig(
          name = configName,
          application = "keel",
          serviceAccount = "keel@spinnaker",
          artifacts = setOf(DockerArtifact(name = "org/thing-1", deliveryConfigName = configName, reference = "thing")),
          environments = setOf(
            SubmittedEnvironment(
              name = "test",
              resources = setOf(
                SubmittedResource(
                  metadata = mapOf("serviceAccount" to "keel@spinnaker"),
                  kind = TEST_API_V1.qualify("whatever"),
                  spec = DummyResourceSpec(data = "o hai")
                )
              ),
              constraints = emptySet()
            )
          )
        )

        val submittedConfig2 = submittedConfig1.copy(name = "double-trouble")
        test("an error is thrown and config is not persisted") {
          subject.upsertDeliveryConfig(submittedConfig1)
          expectCatching {
            subject.upsertDeliveryConfig(submittedConfig2)
          }.failed()
            .isA<TooManyDeliveryConfigsException>()

          expectThat(subject.getDeliveryConfigForApplication("keel").name).isEqualTo(configName)
        }
      }
    }
  }
}
