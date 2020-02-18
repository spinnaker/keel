package com.netflix.spinnaker.keel.persistence

// todo eb: move these tests to combined repo tests
// abstract class CleanerTests<D : DeliveryConfigRepository, R : ResourceRepository, A : ArtifactRepository> :
//  JUnit5Minutests {
//
//  abstract fun createDeliveryConfigRepository(resourceTypeIdentifier: ResourceTypeIdentifier): D
//  abstract fun createResourceRepository(): R
//  abstract fun createArtifactRepository(): A
//
//  open fun flush() {}
//
//  val configName = "my-config"
//
//  val artifact = DockerArtifact(name = "org/image", deliveryConfigName = configName)
//  val newArtifact = artifact.copy(reference = "myart")
//  val firstResource = resource()
//  val secondResource = resource()
//  val firstEnv = Environment(name = "env1", resources = setOf(firstResource))
//  val secondEnv = Environment(name = "env2", resources = setOf(secondResource))
//  val deliveryConfig = DeliveryConfig(
//    name = configName,
//    application = "fnord",
//    serviceAccount = "keel@spinnaker",
//    artifacts = setOf(artifact),
//    environments = setOf(firstEnv)
//  )
//
//  data class Fixture<T : DeliveryConfigRepository, R : ResourceRepository, A : ArtifactRepository>(
//    val deliveryConfigRepositoryProvider: (ResourceTypeIdentifier) -> T,
//    val resourceRepositoryProvider: () -> R,
//    val artifactRepositoryProvider: () -> A
//  ) {
//    private val clock: Clock = Clock.systemDefaultZone()
//    private val publisher: ApplicationEventPublisher = mockk(relaxUnitFun = true)
//
//    private val resourceTypeIdentifier: ResourceTypeIdentifier =
//      object : ResourceTypeIdentifier {
//        override fun identify(apiVersion: String, kind: String): Class<out ResourceSpec> {
//          return when (kind) {
//            "security-group" -> DummyResourceSpec::class.java
//            "cluster" -> DummyResourceSpec::class.java
//            else -> error("unsupported kind $kind")
//          }
//        }
//      }
//
//    internal val deliveryConfigRepository: T = deliveryConfigRepositoryProvider(resourceTypeIdentifier)
//    internal val resourceRepository: R = resourceRepositoryProvider()
//    internal val artifactRepository: A = artifactRepositoryProvider()
//
//    val subject = Cleaner(
//      deliveryConfigRepository,
//      artifactRepository,
//      resourceRepository,
//      clock,
//      publisher
//    )
//
//    fun persist(deliveryConfig: DeliveryConfig) {
//      deliveryConfig.artifacts.forEach {
//        artifactRepository.register(it)
//      }
//      deliveryConfig.resources.forEach {
//        resourceRepository.store(it)
//      }
//      deliveryConfigRepository.store(deliveryConfig)
//    }
//  }
//
//  fun tests() = rootContext<Fixture<D, R, A>>() {
//    fixture {
//      Fixture(
//        deliveryConfigRepositoryProvider = this@CleanerTests::createDeliveryConfigRepository,
//        resourceRepositoryProvider = this@CleanerTests::createResourceRepository,
//        artifactRepositoryProvider = this@CleanerTests::createArtifactRepository
//      )
//    }
//
//    before {
//      persist(deliveryConfig)
//    }
//
//    after {
//      flush()
//    }
//
//    context("delivery config was created then deleted") {
//      before {
//        subject.delete(configName)
//      }
//      test("everything is deleted") {
//        expectThrows<NoSuchDeliveryConfigException> { deliveryConfigRepository.get(configName) }
//        expectThrows<NoSuchResourceException> { resourceRepository.get(firstResource.id) }
//        expectThat(artifactRepository.get(artifact.name, artifact.type, configName)).isEmpty()
//      }
//    }
//
//    context("delivery config is updated") {
//      context("artifact and resource have changed") {
//        before {
//          val updatedConfig = deliveryConfig.copy(
//            artifacts = setOf(newArtifact),
//            environments = setOf(firstEnv.copy(resources = setOf(secondResource)))
//          )
//          persist(updatedConfig)
//          subject.removeResources(old = deliveryConfig, new = updatedConfig)
//        }
//
//        test("no longer present resources are removed") {
//          expectThrows<NoSuchResourceException> { resourceRepository.get(firstResource.id) }
//          expectThrows<ArtifactNotFoundException> { artifactRepository.get(name = artifact.name, type = artifact.type, reference = "org/image", deliveryConfigName = configName) }
//        }
//
//        test("correct resources still exist") {
//          expectCatching { resourceRepository.get(secondResource.id) }.succeeded()
//          expectCatching {
//            artifactRepository.get(name = newArtifact.name, type = newArtifact.type, reference = "myart", deliveryConfigName = configName)
//          }.succeeded()
//        }
//      }
//
//      context("environment changed and artifact removed") {
//        before {
//          val updatedConfig = deliveryConfig.copy(
//            artifacts = setOf(),
//            environments = setOf(firstEnv.copy(name = "env2"))
//          )
//          persist(updatedConfig)
//          subject.removeResources(old = deliveryConfig, new = updatedConfig)
//        }
//        test("old environment is gone") {
//          val config = deliveryConfigRepository.get(configName)
//          expect {
//            that(config.environments.size).isEqualTo(1)
//            that(config.environments.first().name).isEqualTo("env2")
//            that(config.resources.size).isEqualTo(1)
//            that(config.resources.first().id).isEqualTo(firstResource.id)
//            that(config.artifacts.size).isEqualTo(0)
//            that(artifactRepository.get(artifact.name, artifact.type, configName)).isEmpty()
//          }
//        }
//      }
//    }
//  }
// }
