package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.NotificationFrequency
import com.netflix.spinnaker.keel.api.NotificationType
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceKind.Companion.parseKind
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.artifacts.ArtifactOriginFilterSpec
import com.netflix.spinnaker.keel.api.artifacts.BranchFilterSpec
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.api.constraints.ConstraintState
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.api.plugins.kind
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.core.api.ApplicationSummary
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.ManualJudgementConstraint
import com.netflix.spinnaker.keel.events.ApplicationActuationPaused
import com.netflix.spinnaker.keel.events.ResourceCreated
import com.netflix.spinnaker.keel.pause.PauseScope
import com.netflix.spinnaker.keel.resources.ResourceSpecIdentifier
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import com.netflix.spinnaker.keel.test.resource
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expect
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.contains
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.first
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isSuccess
import strikt.assertions.map
import strikt.assertions.none
import java.time.Duration
import java.time.Instant

abstract class DeliveryConfigRepositoryTests<T : DeliveryConfigRepository, R : ResourceRepository, A : ArtifactRepository, P : PausedRepository> :
  JUnit5Minutests {

  abstract fun createDeliveryConfigRepository(resourceSpecIdentifier: ResourceSpecIdentifier): T
  abstract fun createResourceRepository(resourceSpecIdentifier: ResourceSpecIdentifier): R
  abstract fun createArtifactRepository(): A
  abstract fun createPausedRepository(): P

  open fun flush() {}

  data class DummyVerification(
    override val id: String = "whatever"
  ) : Verification {
    override val type = "verification"
  }

  data class Fixture<T : DeliveryConfigRepository, R : ResourceRepository, A : ArtifactRepository, P : PausedRepository>(
    val deliveryConfigRepositoryProvider: (ResourceSpecIdentifier) -> T,
    val resourceRepositoryProvider: (ResourceSpecIdentifier) -> R,
    val artifactRepositoryProvider: () -> A,
    val pausedRepositoryProvider: () -> P,

    val deliveryConfig: DeliveryConfig = DeliveryConfig(
      name = "fnord",
      application = "fnord",
      serviceAccount = "keel@spinnaker",
      metadata = mapOf("some" to "meta")
    )
  ) {
    private val resourceSpecIdentifier: ResourceSpecIdentifier =
      ResourceSpecIdentifier(
        kind<DummyResourceSpec>("ec2/security-group@v1"),
        kind<DummyResourceSpec>("ec2/cluster@v1")
      )

    internal val repository: T = deliveryConfigRepositoryProvider(resourceSpecIdentifier)
    val resourceRepository: R = resourceRepositoryProvider(resourceSpecIdentifier)
    val pausedRepository: P = pausedRepositoryProvider()
    internal val artifactRepository: A = artifactRepositoryProvider()
    internal val clock = MutableClock()

    val artifact: DebianArtifact = DebianArtifact(
      name = "keel",
      deliveryConfigName = deliveryConfig.name,
      vmOptions = VirtualMachineOptions(baseOs = "bionic", regions = setOf("us-west-2"))
    )

    val artifactFromBranch: DebianArtifact = artifact.copy(
      name = "frombranch",
      reference = "frombranch",
      from = ArtifactOriginFilterSpec(branch = BranchFilterSpec(name = "main"))
    )

    fun getByName() = expectCatching {
      repository.get(deliveryConfig.name)
    }

    fun getByApplication() = expectCatching {
      repository.getByApplication(deliveryConfig.application)
    }

    fun store() {
      repository.store(deliveryConfig)
    }

    fun storeResources() {
      deliveryConfig.environments.flatMap { it.resources }.forEach {
        resourceRepository.store(it)
      }
    }

    fun storeArtifacts() {
      deliveryConfig.artifacts.forEach {
        artifactRepository.register(it)
      }
    }

    fun storeJudgements() =
      storeArtifactVersionsAndJudgements(artifact, 1, 1)

    fun storeArtifactVersionsAndJudgements(artifact: DeliveryArtifact, start: Int, end: Int) {
      val range = if (start < end) { start..end } else { start downTo end }
      range.forEach { v ->
        clock.tickMinutes(1)

        artifactRepository.storeArtifactVersion(
          PublishedArtifact(artifact.name, artifact.type, "${artifact.name}-1.0.$v", createdAt = clock.instant(),
            gitMetadata = GitMetadata(commit = "ignored", branch = "main")
          )
        )

        deliveryConfig.environments.forEach { env ->
          repository.storeConstraintState(
            ConstraintState(
              deliveryConfigName = deliveryConfig.name,
              environmentName = env.name,
              artifactVersion = "${artifact.name}-1.0.$v",
              artifactReference = artifact.reference,
              type = "manual-judgement",
              status = ConstraintStatus.PENDING
            )
          )
        }
      }
    }

    fun queueConstraintApproval() {
      repository.queueArtifactVersionForApproval(deliveryConfig.name, "staging", artifact, "keel-1.0.1")
    }

    fun getEnvironment(resource: Resource<*>) = expectCatching {
      repository.environmentFor(resource.id)
    }

    fun getDeliveryConfig(resource: Resource<*>) = expectCatching {
      repository.deliveryConfigFor(resource.id)
    }
  }

  fun tests() = rootContext<Fixture<T, R, A, P>>() {
    fixture {
      Fixture(
        deliveryConfigRepositoryProvider = this@DeliveryConfigRepositoryTests::createDeliveryConfigRepository,
        resourceRepositoryProvider = this@DeliveryConfigRepositoryTests::createResourceRepository,
        artifactRepositoryProvider = this@DeliveryConfigRepositoryTests::createArtifactRepository,
        pausedRepositoryProvider = this@DeliveryConfigRepositoryTests::createPausedRepository
      )
    }

    after {
      flush()
    }

    context("an empty repository") {
      test("retrieving config by name fails") {
        getByName()
          .isFailure()
          .isA<NoSuchDeliveryConfigException>()
      }

      test("retrieving config by application returns an empty list") {
        getByApplication()
          .isFailure()
          .isA<NoSuchDeliveryConfigException>()
      }

      test("recheck does not throw an exception") {
        expectCatching { repository.triggerRecheck("whatever") }.isSuccess()
      }
    }

    context("storing a delivery config with no artifacts or environments") {
      before {
        store()
      }

      test("the config can be retrieved by name") {
        getByName()
          .isSuccess()
          .and {
            get { name }.isEqualTo(deliveryConfig.name)
            get { application }.isEqualTo(deliveryConfig.application)
            get { metadata }.isNotEmpty()
          }
      }

      test("the config can be retrieved by application") {
        getByApplication()
          .isSuccess()
          .and {
            get { name }.isEqualTo(deliveryConfig.name)
            get { application }.isEqualTo(deliveryConfig.application)
            get { metadata }.isNotEmpty()
          }
      }

      test("config can be rechecked") {
        val firstCheck = repository.itemsDueForCheck(Duration.ofMinutes(2), 1)
        val secondCheck = repository.itemsDueForCheck(Duration.ofMinutes(2), 1)
        repository.triggerRecheck(deliveryConfig.application)
        val afterRecheck = repository.itemsDueForCheck(Duration.ofMinutes(2), 1)

        expect {
          that(firstCheck.size).isEqualTo(1)
          that(secondCheck.size).isEqualTo(0)
          that(afterRecheck.size).isEqualTo(1)
          that(afterRecheck.first().application).isEqualTo(deliveryConfig.application)
        }
      }
    }

    context("storing a delivery config with artifacts and environments") {
      deriveFixture {
        copy(
          deliveryConfig = deliveryConfig.copy(
            artifacts = setOf(
              artifact, artifactFromBranch
            ),
            environments = setOf(
              Environment(
                name = "test",
                resources = setOf(
                  resource(kind = parseKind("ec2/cluster@v1")),
                  resource(kind = parseKind("ec2/security-group@v1"))
                )
              ),
              Environment(
                name = "staging",
                constraints = setOf(
                  DependsOnConstraint(
                    environment = "test"
                  ),
                  ManualJudgementConstraint()
                ),
                verifyWith = listOf(
                  DummyVerification()
                ),
                resources = setOf(
                  resource(kind = parseKind("ec2/cluster@v1")),
                  resource(kind = parseKind("ec2/security-group@v1"))
                )
              )
            )
          )
        )
      }

      test("resources in an environment can be rechecked") {
        // note: this test needs to be here even though it's testing a resource repository function
        // because we need a valid config and environment for the resources to exist in, and those can only
        // be saved with both a delivery config repository and a resource repository.
        storeResources()
        store()

        val firstCheck = resourceRepository.itemsDueForCheck(Duration.ofMinutes(2), 4)
        val secondCheck = resourceRepository.itemsDueForCheck(Duration.ofMinutes(2), 4)
        resourceRepository.triggerResourceRecheck("test", deliveryConfig.application)
        val afterRecheck = resourceRepository.itemsDueForCheck(Duration.ofMinutes(2), 4)
        val testResources = deliveryConfig.environments.find { it.name == "test" }?.resourceIds ?: emptySet()

        expect {
          that(firstCheck.size).isEqualTo(4)
          that(secondCheck.size).isEqualTo(0)
          that(afterRecheck.size).isEqualTo(2) //only one environment had a recheck triggered
          that(afterRecheck.first().application).isEqualTo(deliveryConfig.application)
          that(afterRecheck.map { it.id }.toList()).containsExactlyInAnyOrder(testResources)
        }
      }

      context("updating an existing delivery config") {
        deriveFixture {
          storeArtifacts()
          storeResources()
          store()

          copy(deliveryConfig = deliveryConfig.copy(serviceAccount = "new-service-account@spinnaker.io"))
        }

        before {
          store()
        }

        test("the service account can be updated") {
          getByName()
            .isSuccess()
            .get { serviceAccount }
            .isEqualTo(deliveryConfig.serviceAccount)
        }
      }

      context("the environments did not previously exist") {
        before {
          storeArtifacts()
          storeResources()
          store()
          storeJudgements()
        }

        test("the config can be retrieved by name") {
          getByName()
            .isSuccess()
            .and {
              get { name }.isEqualTo(deliveryConfig.name)
              get { application }.isEqualTo(deliveryConfig.application)
            }
        }

        test("artifacts are attached when retrieved by name") {
          getByName()
            .isSuccess()
            .get { artifacts }.isEqualTo(deliveryConfig.artifacts)
        }

        test("artifacts are attached when retrieved by application") {
          getByApplication()
            .isSuccess()
            .get { artifacts }.isEqualTo(deliveryConfig.artifacts)
        }

        test("environments are attached when retrieved by name") {
          getByName()
            .isSuccess()
            .get { environments }
            .isEqualTo(deliveryConfig.environments)
        }

        test("environments are attached when retrieved by application") {
          getByApplication()
            .isSuccess()
            .get { environments }
            .isEqualTo(deliveryConfig.environments)
        }

        context("artifact constraint flows") {
          test("constraint states can be retrieved and updated") {
            val environment = deliveryConfig.environments.first { it.name == "staging" }
            val recentConstraintState = repository.constraintStateFor(deliveryConfig.name, environment.name)

            expectThat(recentConstraintState)
              .hasSize(1)

            expectThat(recentConstraintState.first().status)
              .isEqualTo(ConstraintStatus.PENDING)

            val constraint = recentConstraintState
              .first()
              .copy(status = ConstraintStatus.PASS)
            repository.storeConstraintState(constraint)
            val appConstraintState = repository.constraintStateFor(deliveryConfig.application)
            val updatedConstraintState = repository.constraintStateFor(deliveryConfig.name, environment.name)

            expectThat(appConstraintState)
              .contains(updatedConstraintState)
            expectThat(updatedConstraintState)
              .hasSize(1)
            expectThat(updatedConstraintState.first().status)
              .isEqualTo(ConstraintStatus.PASS)
          }

          // TODO: this should be removed when the artifactReference is made non-nullable in the repository methods
          test("backwards-compatibility: constraint state can be retrieved with missing artifact reference in the database") {
            val environment = deliveryConfig.environments.first { it.name == "staging" }
            val originalState = repository.getConstraintState(
              deliveryConfig.name, environment.name, "keel-1.0.1", "manual-judgement", "keel"
            )
            val stateWithNullReference = originalState!!.copy(artifactReference = null)
            repository.storeConstraintState(stateWithNullReference)
            val updatedState = repository.getConstraintState(
              deliveryConfig.name, environment.name, "keel-1.0.1", "manual-judgement", "keel"
            )
            expectThat(updatedState).isNotNull()
            expectThat(updatedState!!.artifactReference).isNull()
          }

          // TODO: this should be removed when the artifactReference is made non-nullable in the repository methods
          test("backwards-compatibility: constraint state can be retrieved without passing artifact reference") {
            val environment = deliveryConfig.environments.first { it.name == "staging" }
            // This only works currently because the artifactVersion contains the artifact name for debians and makes them
            // unique, but won't work for Docker.
            val constraintState = repository.getConstraintState(
              deliveryConfig.name, environment.name, "keel-1.0.1", "manual-judgement", null
            )
            expectThat(constraintState).isNotNull()
            expectThat(constraintState!!.artifactReference).isNotNull()
          }

          test("can queue constraint approvals") {
            queueConstraintApproval()
            expectThat(repository
              .getArtifactVersionsQueuedForApproval(deliveryConfig.name, "staging", artifact)
              .map { it.version }
            ).isEqualTo(listOf("keel-1.0.1"))
          }

          context("with artifact filtered by status") {
            before {
              storeArtifactVersionsAndJudgements(artifact, 1, 5)
            }

            test("can retrieve sorted pending artifact versions") {
              expectThat(
                repository.getPendingArtifactVersions(deliveryConfig.name, "staging", artifact)
                  .map { it.version }
              )
                .isEqualTo((1..5).map { "${artifact.name}-1.0.$it" }.reversed())
            }

            test("can retrieve sorted artifact versions queued for approval") {
              (1..5).forEach { v ->
                repository.queueArtifactVersionForApproval(
                  deliveryConfig.name, "staging", artifact, "${artifact.name}-1.0.$v")
              }
              expectThat(
                repository.getArtifactVersionsQueuedForApproval(deliveryConfig.name, "staging", artifact)
                  .map { it.version }
              )
                .isEqualTo((1..5).map { "${artifact.name}-1.0.$it" }.reversed())
            }
          }

          context("with artifact filtered by branch") {
            before {
              storeArtifactVersionsAndJudgements(artifactFromBranch, 5, 1) // versions in reverse order of time
            }

            test("can retrieve pending artifact versions sorted by timestamp") {
              expectThat(
                repository.getPendingArtifactVersions(deliveryConfig.name, "staging", artifactFromBranch)
                  .map { it.version }
              )
                .isEqualTo((1..5).map { "${artifactFromBranch.name}-1.0.$it" })
            }

            test("can retrieve sorted artifact versions queued for approval") {
              (1..5).forEach { v ->
                repository.queueArtifactVersionForApproval(
                  deliveryConfig.name, "staging", artifactFromBranch, "${artifactFromBranch.name}-1.0.$v")
              }
              expectThat(
                repository.getArtifactVersionsQueuedForApproval(deliveryConfig.name, "staging", artifactFromBranch)
                  .map { it.version }
              )
                .isEqualTo((1..5).map { "${artifactFromBranch.name}-1.0.$it" })
            }
          }
        }

        test("can retrieve the environment for the resources") {
          val environment = deliveryConfig.environments.first { it.name == "test" }
          val resource = environment.resources.random()

          getEnvironment(resource)
            .isSuccess()
            .isEqualTo(environment)
        }

        test("can retrieve the manifest for the resources") {
          val resource = deliveryConfig.resources.random()
          getDeliveryConfig(resource)
            .isSuccess()
            .get { name }
            .isEqualTo(deliveryConfig.name)
        }
      }

      context("environments already existed") {
        before {
          storeArtifacts()
          storeResources()
          store()
          store()
        }

        test("the environments are not duplicated") {
          getByName()
            .isSuccess()
            .get { environments }.hasSize(deliveryConfig.environments.size)
        }
      }

      context("deleting a delivery config") {
        before {
          storeResources()
          store()
          resourceRepository.appendHistory(ApplicationActuationPaused(deliveryConfig.application, "test"))
          pausedRepository.pauseApplication(deliveryConfig.application, "test")
          deliveryConfig.resources.forEach { resource ->
            resourceRepository.appendHistory(ResourceCreated(resource))
            pausedRepository.pauseResource(resource.id, "test")
          }
        }

        context("by application name") {
          test("deletes data successfully for known application") {
            repository.deleteByApplication(deliveryConfig.application)
            getByName()
              .isFailure()
          }

          test("throws exception for unknown application") {
            expectThrows<NoSuchDeliveryConfigException> {
              repository.deleteByApplication("notfound")
            }
          }

          test("deletes related application events") {
            repository.deleteByApplication(deliveryConfig.application)
            expectThat(resourceRepository.applicationEventHistory(deliveryConfig.application)).isEmpty()
          }

          test("deletes related application pause records") {
            repository.deleteByApplication(deliveryConfig.application)
            expectThat(pausedRepository.getPause(PauseScope.APPLICATION, deliveryConfig.application)).isNull()
          }

          test("deletes related resource events") {
            repository.deleteByApplication(deliveryConfig.application)
            // FIXME: can't check resource event history because it tries to read the resource record first.
            //  We should break out the event stuff into its own repository.
            // expectThat(resourceRepository.eventHistory(firstResource.id)).isEmpty()
          }

          test("deletes related resource pause records") {
            repository.deleteByApplication(deliveryConfig.application)
            deliveryConfig.resources.forEach { resource ->
              expectThat(pausedRepository.getPause(PauseScope.RESOURCE, resource.id)).isNull()
            }
          }

          test("deletes any older versions of resources") {
            with(deliveryConfig.resources.first()) {
              val newResourceVersion = resource(
                kind = kind,
                id = spec.id,
                application = application
              )
              resourceRepository.store(newResourceVersion)
            }

            expectThat(repository.get(deliveryConfig.name).resources)
              .hasSize(4)
              .map { it.version }
              .containsExactlyInAnyOrder(1, 1, 1, 2)

            repository.deleteByApplication(deliveryConfig.application)

            expectThat(resourceRepository.getResourcesByApplication(deliveryConfig.application)).isEmpty()
          }
        }

        context("by delivery config name") {
          test("deletes data successfully for known delivery config") {
            repository.deleteByName(deliveryConfig.name)
            getByName()
              .isFailure()
          }

          test("throws exception for unknown delivery config") {
            expectThrows<NoSuchDeliveryConfigException> {
              repository.deleteByName("notfound")
            }
          }
        }
      }

      context("updating an existing delivery config to move a resource from one environment to another") {
        deriveFixture {
          storeArtifacts()
          storeResources()
          store()

          val movedResources = deliveryConfig
            .resources
            .filter { it.kind == parseKind("ec2/security-group@v1") }
            .toSet()
          val newEnvironments = deliveryConfig
            .environments
            .map {
              it.copy(resources = it.resources - movedResources)
            } + Environment(
            name = "infrastructure",
            resources = movedResources
          )

          copy(
            deliveryConfig = deliveryConfig.run {
              copy(environments = newEnvironments.toSet())
            }
          )
        }

        before {
          store()
        }

        test("the resources now appear in the environment they were moved to") {
          getByName()
            .isSuccess()
            .get { environments.first { it.name == "infrastructure" }.resources }
            .hasSize(2)
        }

        listOf("test", "staging").forEach { environmentName ->
          test("the resources no longer appear in the environments they were moved from") {
            getByName()
              .isSuccess()
              .get { environments.first { it.name == environmentName }.resources }
              .hasSize(1)
              .none {
                get { kind } isEqualTo parseKind("ec2/security-group@v1")
              }
          }
        }
      }

      context("updating an existing delivery config with a new version of a resource") {
        before {
          storeArtifacts()
          storeResources()
          store()

          val resource = deliveryConfig.environments.first().resources.first { it.kind == parseKind("ec2/cluster@v1") }
            .run {
              copy(spec = DummyResourceSpec(id = id, application = application))
            }

          resourceRepository.store(resource)
        }

        test("retrieving the delivery config includes the new version of the resource") {
          getByName()
            .isSuccess()
            .get { resources.map(Resource<*>::version) }
            .hasSize(deliveryConfig.resources.size)
            .contains(2)
        }
      }

      context("notifications") {
        before {
          repository.store(deliveryConfig.copy(
            environments = setOf(
              Environment(
                name = "prod",
                resources = setOf(
                  resource(kind = parseKind("ec2/cluster@v1")),
                  resource(kind = parseKind("ec2/security-group@v1"))
                ),
                notifications = setOf(NotificationConfig(
                  type = NotificationType.slack,
                  address = "test",
                  frequency = NotificationFrequency.verbose
                ),
                  NotificationConfig(
                    type = NotificationType.email,
                    address = "test",
                    frequency = NotificationFrequency.quiet
                  ))
              ),
            )
          ))
        }
        test ("get stored notifications") {
          expectThat(repository.environmentNotifications(deliveryConfig.name, "prod").size)
            .isEqualTo(2)
        }

        test ("environment without notifications will return an empty set") {
          expectThat(repository.environmentNotifications(deliveryConfig.name, "staging"))
            .isEmpty()
        }
      }
    }

    context("delivery config with resources exists") {
      deriveFixture {
        val resources = setOf(
          resource(kind = parseKind("ec2/cluster@v1")),
          resource(kind = parseKind("ec2/security-group@v1"))
        )
        copy(
          deliveryConfig = deliveryConfig.copy(
            environments = setOf(
              Environment(
                name = "test",
                resources = resources
              ),
              Environment(
                name = "staging",
                resources = resources
              )
            )
          )
        )
      }

      before {
        store()
        storeResources()
      }

      test("application summary can be retrieved successfully") {
        val appSummary = with(repository.get(deliveryConfig.name)) {
          ApplicationSummary(
            deliveryConfigName = name,
            application = application,
            serviceAccount = serviceAccount,
            apiVersion = apiVersion,
            createdAt = metadata["createdAt"] as Instant,
            resourceCount = 4, // 2 from test, 2 from staging
            isPaused = false
          )
        }

        expectThat(repository.getApplicationSummaries())
          .hasSize(1)
          .first()
          .isEqualTo(appSummary)
      }
    }
  }
}
