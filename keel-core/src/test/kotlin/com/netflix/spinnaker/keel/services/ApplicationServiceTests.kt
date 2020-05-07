package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus.RELEASE
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.constraints.ConstraintEvaluator
import com.netflix.spinnaker.keel.constraints.ConstraintState
import com.netflix.spinnaker.keel.constraints.ConstraintStatus.NOT_EVALUATED
import com.netflix.spinnaker.keel.constraints.ConstraintStatus.OVERRIDE_PASS
import com.netflix.spinnaker.keel.constraints.ConstraintStatus.PENDING
import com.netflix.spinnaker.keel.constraints.SupportedConstraintType
import com.netflix.spinnaker.keel.constraints.UpdatedConstraintStatus
import com.netflix.spinnaker.keel.core.api.ArtifactSummary
import com.netflix.spinnaker.keel.core.api.ArtifactSummaryInEnvironment
import com.netflix.spinnaker.keel.core.api.ArtifactVersionStatus
import com.netflix.spinnaker.keel.core.api.ArtifactVersionSummary
import com.netflix.spinnaker.keel.core.api.ArtifactVersions
import com.netflix.spinnaker.keel.core.api.BuildMetadata
import com.netflix.spinnaker.keel.core.api.DependOnConstraintMetadata
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.EnvironmentSummary
import com.netflix.spinnaker.keel.core.api.GitMetadata
import com.netflix.spinnaker.keel.core.api.ManualJudgementConstraint
import com.netflix.spinnaker.keel.core.api.PipelineConstraint
import com.netflix.spinnaker.keel.core.api.StatefulConstraintSummary
import com.netflix.spinnaker.keel.core.api.StatelessConstraintSummary
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.test.artifactReferenceResource
import com.netflix.spinnaker.keel.test.versionedArtifactResource
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.experimental.SKIP
import dev.minutest.experimental.minus
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.all
import strikt.assertions.first
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotNull

class ApplicationServiceTests : JUnit5Minutests {
  class Fixture {
    val clock: MutableClock = MutableClock(
      Instant.parse("2020-03-25T00:00:00.00Z"),
      ZoneId.of("UTC")
    )
    val repository: KeelRepository = mockk()
    val resourceHistoryService: ResourceHistoryService = mockk()

    val application = "fnord"
    val artifact = DebianArtifact(
      name = application,
      deliveryConfigName = "manifest",
      reference = "fnord",
      statuses = setOf(RELEASE),
      vmOptions = VirtualMachineOptions(
        baseOs = "xenial",
        regions = setOf("us-west-2", "us-east-1")
      )
    )

    val environments = listOf("test", "staging", "production").associateWith { name ->
      Environment(
        name = name,
        constraints = if (name == "production") {
          setOf(
            DependsOnConstraint("staging"),
            ManualJudgementConstraint(),
            PipelineConstraint(pipelineId = "fakePipeline")
          )
        } else {
          emptySet()
        },
        resources = setOf(
          // resource with new-style artifact reference
          artifactReferenceResource(),
          // resource with old-style image provider
          versionedArtifactResource()
        )
      )
    }

    val deliveryConfig = DeliveryConfig(
      name = "manifest",
      application = application,
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(artifact),
      environments = environments.values.toSet()
    )

    val version0 = "fnord-1.0.0-h0.a0a0a0a"
    val version1 = "fnord-1.0.1-h1.b1b1b1b"
    val version2 = "fnord-1.0.2-h2.c2c2c2c"
    val version3 = "fnord-1.0.3-h3.d3d3d3d"
    val version4 = "fnord-1.0.4-h4.e4e4e4e"

    val versions = listOf(version0, version1, version2, version3, version4)

    val dependsOnEvaluator = mockk<ConstraintEvaluator<DependsOnConstraint>>() {
      every { isImplicit() } returns false
      every { supportedType } returns SupportedConstraintType<DependsOnConstraint>("depends-on")
    }

    // subject
    val applicationService = ApplicationService(repository, resourceHistoryService, listOf(dependsOnEvaluator))
  }

  fun applicationServiceTests() = rootContext<Fixture> {
    fixture {
      Fixture()
    }

    before {
      every { repository.getDeliveryConfigForApplication(application) } returns deliveryConfig
    }

    context("artifact summaries by application") {
      before {
        // repository.artifactVersions(artifact)
        every { repository.artifactVersions(artifact) } returns listOf(version0, version1, version2, version3, version4)
      }

      context("all versions are pending in all environments") {
        before {
          every {
            repository.getEnvironmentSummaries(deliveryConfig)
          } returns deliveryConfig.environments.map { env ->
            toEnvironmentSummary(env)
          }

          every {
            repository.constraintStateFor(deliveryConfig.name, any(), any<String>())
          } returns emptyList()

          every {
            dependsOnEvaluator.canPromote(artifact, any(), deliveryConfig, any())
          } returns true
        }

        test("artifact summary shows all versions pending in all environments") {
          val summaries = applicationService.getArtifactSummariesFor(application)

          expectThat(summaries) {
            hasSize(1)
            first().and { // TODO: replace with withFirst when Strikt > 0.26.0 hits
              get { name }.isEqualTo(artifact.name)
              with(ArtifactSummary::versions) {
                hasSize(versions.size)
                all {
                  with(ArtifactVersionSummary::environments) {
                    hasSize(environments.size)
                    all {
                      get { state }.isEqualTo(PENDING.name.toLowerCase())
                    }
                  }
                }
              }
            }
          }
        }
      }

      // pending - no further repository calls, it just returns a synthesized summary
      // skipped 1. repository.getArtifactSummaryInEnvironment returns something and we use that
      // skipped 2. repository.getArtifactSummaryInEnvironment returns nothing, we synthesize a summary
      // skipped 2. repository.getArtifactSummaryInEnvironment returns "pending", we synthesize a summary
      // else - we return whatever repository.getArtifactSummaryInEnvironment gives us
      // after all that we add stateless, and stateful constraint summaries
      //
    }

    SKIP - context("delivery config exists and there has been activity") {
      before {
        every { repository.getDeliveryConfigForApplication(application) } returns deliveryConfig
        // these events are required because Resource.toResourceSummary() relies on events to determine resource status
//        deliveryConfig.environments.flatMap { it.resources }.forEach { resource ->
//          repository.appendResourceHistory(ResourceValid(resource))
//        }
//        repository.storeArtifact(artifact, version0, RELEASE)
//        repository.storeArtifact(artifact, version1, RELEASE)
//        repository.storeArtifact(artifact, version2, RELEASE)
//        repository.storeArtifact(artifact, version3, RELEASE)
//        repository.storeArtifact(artifact, version4, RELEASE)

        // with our fake clock moving forward, simulate artifact approvals and deployments
        // v0
//        repository.markAsSuccessfullyDeployedTo(deliveryConfig, artifact, version0, "test")
//        clock.tickHours(1) // 2020-03-25T01:00:00.00Z
//        repository.markAsSuccessfullyDeployedTo(deliveryConfig, artifact, version0, "staging")
//        val productionDeployed = clock.tickHours(1) // 2020-03-25T02:00:00.00Z
//        repository.markAsSuccessfullyDeployedTo(deliveryConfig, artifact, version0, "production")

        // v1 skipped by v2
//        clock.tickHours(1) // 2020-03-25T03:00:00.00Z
//        repository.markAsSkipped(deliveryConfig, artifact, version1, "test", version2)
//        repository.markAsSuccessfullyDeployedTo(deliveryConfig, artifact, version2, "test")
//        clock.tickHours(1) // 2020-03-25T04:00:00.00Z
//        repository.markAsSuccessfullyDeployedTo(deliveryConfig, artifact, version2, "staging")
//        clock.tickHours(1) // 2020-03-25T05:00:00.00Z
//        repository.markAsSuccessfullyDeployedTo(deliveryConfig, artifact, version3, "test")
//
//        repository.approveVersionFor(deliveryConfig, artifact, version4, "test")
//        repository.storeConstraintState(
//          ConstraintState(
//            deliveryConfigName = deliveryConfig.name,
//            environmentName = "production",
//            artifactVersion = version0,
//            type = "manual-judgement",
//            status = OVERRIDE_PASS,
//            createdAt = clock.start,
//            judgedAt = productionDeployed.minus(Duration.ofMinutes(30)),
//            judgedBy = "lpollo@acme.com",
//            comment = "Aye!"
//          )
//        )
      }

      SKIP - test("can get environment summaries by application") {
        val summaries = applicationService.getEnvironmentSummariesFor(application)

        val production = summaries.find { it.name == "production" }
        val staging = summaries.find { it.name == "staging" }
        val test = summaries.find { it.name == "test" }

        val expectedProd = EnvironmentSummary(
          deliveryConfig.environments.find { it.name == "production" }!!,
          setOf(ArtifactVersions(
            name = artifact.name,
            type = artifact.type,
            reference = artifact.reference,
            statuses = artifact.statuses,
            versions = ArtifactVersionStatus(
              current = version0,
              pending = listOf(version1, version2, version3, version4),
              approved = listOf(),
              previous = listOf(),
              vetoed = listOf(),
              deploying = null,
              skipped = listOf()
            ),
            pinnedVersion = null
          ))
        )
        val expectedStage = EnvironmentSummary(
          deliveryConfig.environments.find { it.name == "staging" }!!,
          setOf(ArtifactVersions(
            name = artifact.name,
            type = artifact.type,
            reference = artifact.reference,
            statuses = artifact.statuses,
            versions = ArtifactVersionStatus(
              current = version2,
              pending = listOf(version3, version4),
              approved = listOf(),
              previous = listOf(version0),
              vetoed = listOf(),
              deploying = null,
              skipped = listOf(version1)
            ),
            pinnedVersion = null
          ))
        )
        val expectedTest = EnvironmentSummary(
          deliveryConfig.environments.find { it.name == "test" }!!,
          setOf(ArtifactVersions(
            name = artifact.name,
            type = artifact.type,
            reference = artifact.reference,
            statuses = artifact.statuses,
            versions = ArtifactVersionStatus(
              current = version3,
              pending = listOf(),
              approved = listOf(version4),
              previous = listOf(version0, version2),
              vetoed = listOf(),
              deploying = null,
              skipped = listOf(version1)
            ),
            pinnedVersion = null
          ))
        )
        expect {
          that(summaries.size).isEqualTo(3)
          that(production).isNotNull()
          that(production).isEqualTo(expectedProd)
          that(staging).isNotNull()
          that(staging).isEqualTo(expectedStage)
          that(test).isNotNull()
          that(test).isEqualTo(expectedTest)
        }
      }

      SKIP - test("can get artifact summaries by application") {
        val summaries = applicationService.getArtifactSummariesFor(application)
        val v4 = ArtifactVersionSummary(
          version = version4,
          displayName = "1.0.4",
          environments = setOf(
            ArtifactSummaryInEnvironment(environment = "test", version = version4, state = "approved"),
            ArtifactSummaryInEnvironment(environment = "staging", version = version4, state = "pending"),
            ArtifactSummaryInEnvironment(environment = "production", version = version4, state = "pending",
              statefulConstraints = listOf(StatefulConstraintSummary("manual-judgement", NOT_EVALUATED), StatefulConstraintSummary("pipeline", NOT_EVALUATED)),
              statelessConstraints = listOf(StatelessConstraintSummary("depends-on", false, DependOnConstraintMetadata("staging"))))
          ),
          build = BuildMetadata(4),
          git = GitMetadata("e4e4e4e")
        )
        val v3 = ArtifactVersionSummary(
          version = version3,
          displayName = "1.0.3",
          environments = setOf(
            ArtifactSummaryInEnvironment(environment = "test", version = version3, state = "current", deployedAt = Instant.parse("2020-03-25T05:00:00Z")),
            ArtifactSummaryInEnvironment(environment = "staging", version = version3, state = "pending"),
            ArtifactSummaryInEnvironment(environment = "production", version = version3, state = "pending",
              statefulConstraints = listOf(StatefulConstraintSummary("manual-judgement", NOT_EVALUATED), StatefulConstraintSummary("pipeline", NOT_EVALUATED)),
              statelessConstraints = listOf(StatelessConstraintSummary("depends-on", false, DependOnConstraintMetadata("staging"))))
          ),
          build = BuildMetadata(3),
          git = GitMetadata("d3d3d3d")
        )
        val v2 = ArtifactVersionSummary(
          version = version2,
          displayName = "1.0.2",
          environments = setOf(
            ArtifactSummaryInEnvironment(environment = "test", version = version2, state = "previous", deployedAt = Instant.parse("2020-03-25T03:00:00Z"), replacedAt = Instant.parse("2020-03-25T05:00:00Z"), replacedBy = version3),
            ArtifactSummaryInEnvironment(environment = "staging", version = version2, state = "current", deployedAt = Instant.parse("2020-03-25T04:00:00Z")),
            ArtifactSummaryInEnvironment(environment = "production", version = version2, state = "pending",
              statefulConstraints = listOf(StatefulConstraintSummary("manual-judgement", NOT_EVALUATED), StatefulConstraintSummary("pipeline", NOT_EVALUATED)),
              statelessConstraints = listOf(StatelessConstraintSummary("depends-on", true, DependOnConstraintMetadata("staging")))
            )
          ),
          build = BuildMetadata(2),
          git = GitMetadata("c2c2c2c")
        )
        val v1 = ArtifactVersionSummary(
          version = version1,
          displayName = "1.0.1",
          environments = setOf(
            ArtifactSummaryInEnvironment(environment = "test", version = version1, state = "skipped", replacedBy = version2, replacedAt = Instant.parse("2020-03-25T03:00:00Z")),
            ArtifactSummaryInEnvironment(environment = "staging", version = version1, state = "skipped"),
            ArtifactSummaryInEnvironment(environment = "production", version = version1, state = "pending",
              statefulConstraints = listOf(StatefulConstraintSummary("manual-judgement", NOT_EVALUATED), StatefulConstraintSummary("pipeline", NOT_EVALUATED)),
              statelessConstraints = listOf(StatelessConstraintSummary("depends-on", false, DependOnConstraintMetadata("staging")))
            )
          ),
          build = BuildMetadata(1),
          git = GitMetadata("b1b1b1b")
        )
        val v0 = ArtifactVersionSummary(
          version = version0,
          displayName = "1.0.0",
          environments = setOf(
            ArtifactSummaryInEnvironment(environment = "test", version = version0, state = "previous", deployedAt = Instant.parse("2020-03-25T00:00:00Z"), replacedAt = Instant.parse("2020-03-25T03:00:00Z"), replacedBy = version2),
            ArtifactSummaryInEnvironment(environment = "staging", version = version0, state = "previous", deployedAt = Instant.parse("2020-03-25T01:00:00Z"), replacedAt = Instant.parse("2020-03-25T04:00:00Z"), replacedBy = version2),
            ArtifactSummaryInEnvironment(environment = "production", version = version0, state = "current", deployedAt = Instant.parse("2020-03-25T02:00:00Z"),
              statefulConstraints = listOf(StatefulConstraintSummary("manual-judgement", OVERRIDE_PASS, startedAt = Instant.parse("2020-03-25T00:00:00Z"), judgedBy = "lpollo@acme.com", judgedAt = Instant.parse("2020-03-25T01:30:00Z"), comment = "Aye!"), StatefulConstraintSummary("pipeline", NOT_EVALUATED)),
              statelessConstraints = listOf(StatelessConstraintSummary("depends-on", true, DependOnConstraintMetadata("staging"))))
          ),
          build = BuildMetadata(0),
          git = GitMetadata("a0a0a0a")
        )

        expect {
          that(summaries.size).isEqualTo(1)
          that(summaries.first().versions.find { it.version == version4 }).isEqualTo(v4)
          that(summaries.first().versions.find { it.version == version3 }).isEqualTo(v3)
          that(summaries.first().versions.find { it.version == version2 }).isEqualTo(v2)
          that(summaries.first().versions.find { it.version == version1 }).isEqualTo(v1)
          that(summaries.first().versions.find { it.version == version0 }).isEqualTo(v0)
        }
      }

      SKIP - test("no constraints have been evaluated") {
        val states = applicationService.getConstraintStatesFor(application, "prod", 10)
        expectThat(states).isEmpty()
      }

      SKIP - test("pending manual judgement") {
        val judgement = ConstraintState(deliveryConfig.name, "production", version2, "manual-judgement", PENDING)
        repository.storeConstraintState(judgement)

        val states = applicationService.getConstraintStatesFor(application, "production", 10)
        expect {
          that(states).isNotEmpty()
          that(states.size).isEqualTo(2)
          that(states.first().status).isEqualTo(PENDING)
          that(states.first().type).isEqualTo("manual-judgement")
        }
      }

      SKIP - test("approve manual judgement") {
        val judgement = ConstraintState(deliveryConfig.name, "production", version2, "manual-judgement", PENDING)
        repository.storeConstraintState(judgement)

        val updatedState = UpdatedConstraintStatus("manual-judgement", version2, OVERRIDE_PASS)
        applicationService.updateConstraintStatus("keel", application, "production", updatedState)

        val states = applicationService.getConstraintStatesFor(application, "production", 10)
        expect {
          that(states).isNotEmpty()
          that(states.size).isEqualTo(2)
          that(states.first().status).isEqualTo(OVERRIDE_PASS)
          that(states.first().type).isEqualTo("manual-judgement")
        }
      }
    }
  }

  private fun Fixture.toEnvironmentSummary(env: Environment): EnvironmentSummary {
    return EnvironmentSummary(
      env,
      setOf(
        ArtifactVersions(
          name = artifact.name,
          type = artifact.type,
          reference = artifact.reference,
          statuses = artifact.statuses,
          versions = ArtifactVersionStatus(null, null, versions, emptyList(), emptyList(), emptyList(), emptyList()),
          pinnedVersion = null
        )
      )
    )
  }
}
