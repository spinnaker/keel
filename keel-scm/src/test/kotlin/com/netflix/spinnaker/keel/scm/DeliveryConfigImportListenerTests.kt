package com.netflix.spinnaker.keel.scm

import com.netflix.spectator.api.Registry
import com.netflix.spectator.api.Tag
import com.netflix.spinnaker.keel.api.artifacts.ArtifactOriginFilter
import com.netflix.spinnaker.keel.api.artifacts.branchName
import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.SubmittedEnvironment
import com.netflix.spinnaker.keel.front50.Front50Cache
import com.netflix.spinnaker.keel.front50.model.Application
import com.netflix.spinnaker.keel.front50.model.DataSources
import com.netflix.spinnaker.keel.front50.model.ManagedDeliveryConfig
import com.netflix.spinnaker.keel.igor.DeliveryConfigImporter
import com.netflix.spinnaker.keel.igor.ScmService
import com.netflix.spinnaker.keel.igor.model.Branch
import com.netflix.spinnaker.keel.notifications.DeliveryConfigImportFailed
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.scm.DeliveryConfigImportListener.Companion.CODE_EVENT_COUNTER
import com.netflix.spinnaker.keel.test.submittedResource
import com.netflix.spinnaker.kork.exceptions.SystemException
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.TestContextBuilder
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.called
import io.mockk.just
import io.mockk.coEvery as every
import io.mockk.mockk
import io.mockk.runs
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.Environment
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.one
import io.mockk.coVerify as verify

class DeliveryConfigImportListenerTests : JUnit5Minutests {
  class Fixture {
    val repository: KeelRepository = mockk()
    val importer: DeliveryConfigImporter = mockk()
    val front50Cache: Front50Cache = mockk()
    val scmService: ScmService = mockk()
    val springEnv: Environment = mockk()
    val spectator: Registry = mockk()
    val clock = MutableClock()
    val eventPublisher: ApplicationEventPublisher = mockk()
    val subject = DeliveryConfigImportListener(
      repository = repository,
      deliveryConfigImporter = importer,
      front50Cache = front50Cache,
      scmService = scmService,
      springEnv = springEnv,
      spectator = spectator,
      eventPublisher = eventPublisher,
      clock = clock
    )

    val configuredApp = Application(
      name = "fnord",
      email = "keel@keel.io",
      repoType = "stash",
      repoProjectKey = "myorg",
      repoSlug = "myrepo",
      managedDelivery = ManagedDeliveryConfig(importDeliveryConfig = true),
      dataSources = DataSources(enabled = emptyList(), disabled = emptyList())
    )

    val notConfiguredApp = Application(
      name = "notfnord",
      email = "keel@keel.io",
      repoType = "stash",
      repoProjectKey = "myorg",
      repoSlug = "another-repo",
      dataSources = DataSources(enabled = emptyList(), disabled = emptyList())
    )

    val artifactFromMain = DockerArtifact(
      name = "myorg/myartifact",
      deliveryConfigName = "myconfig",
      reference = "myartifact-main",
      from = ArtifactOriginFilter(branch = branchName("main"))
    )

    val deliveryConfig = SubmittedDeliveryConfig(
      application = "fnord",
      name = "myconfig",
      serviceAccount = "keel@keel.io",
      artifacts = setOf(artifactFromMain),
      environments = setOf(
        SubmittedEnvironment(
          name = "test",
          resources = setOf(
            submittedResource()
          )
        )
      )
    )

    val commitEvent = CommitCreatedEvent(
      repoKey = "stash/myorg/myrepo",
      targetBranch = "main",
      commitHash = "1d52038730f431be19a8012f6f3f333e95a53772"
    )

    val commitEventForAnotherBranch = commitEvent.copy(targetBranch = "not-a-match")

    // matches repo for nonConfiguredApp
    val commitEventForAnotherRepo = commitEvent.copy(repoKey = "stash/myorg/another-repo")

    fun setupMocks() {
      every {
        springEnv.getProperty("keel.importDeliveryConfigs.enabled", Boolean::class.java, true)
      } returns true

      every {
        spectator.counter(any(), any<Iterable<Tag>>())
      } returns mockk {
        every {
          increment()
        } just runs
      }

      every {
        eventPublisher.publishEvent(any<Object>())
      } just runs

      every {
        front50Cache.allApplications()
      } returns listOf(configuredApp, notConfiguredApp)

      every {
        importer.import(commitEvent, any())
      } returns deliveryConfig

      every {
        repository.upsertDeliveryConfig(deliveryConfig)
      } returns deliveryConfig.toDeliveryConfig()

      every {
        scmService.getDefaultBranch(configuredApp.repoType!!, configuredApp.repoProjectKey!!, configuredApp.repoSlug!!)
      } returns Branch("main", "refs/heads/main", default = true)
    }
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    context("an application is configured to retrieve the delivery config from source") {
      before {
        setupMocks()
      }

      context("a commit event matching the repo and branch is received") {
        before {
          subject.handleCommitCreated(commitEvent)
        }

        test("the delivery config is imported from the commit in the event") {
          verify(exactly = 1) {
            importer.import(
              commitEvent = commitEvent,
              manifestPath = "spinnaker.yml"
            )
          }
        }

        test("the delivery config is created/updated") {
          verify {
            repository.upsertDeliveryConfig(deliveryConfig)
          }
        }

        test("a successful delivery config retrieval is counted") {
          val tags = mutableListOf<Iterable<Tag>>()
          verify {
            spectator.counter(CODE_EVENT_COUNTER, capture(tags))
          }
          expectThat(tags).one {
            contains(DELIVERY_CONFIG_RETRIEVAL_SUCCESS.toTags())
          }
        }
      }

      context("a commit event NOT matching the app repo is received") {
        before {
          subject.handleCommitCreated(commitEventForAnotherRepo)
        }

        testEventIgnored()
      }

      context("a commit event NOT matching the app default branch is received") {
        before {
          subject.handleCommitCreated(commitEventForAnotherBranch)
        }

        testEventIgnored()
      }
    }

    context("an application is NOT configured to retrieve the delivery config from source") {
      before {
        setupMocks()
      }

      context("a commit event matching the repo and branch is received") {
        before {
          subject.handleCommitCreated(commitEventForAnotherRepo)
        }

        testEventIgnored()
      }
    }

    context("feature flag is disabled") {
      before {
        setupMocks()
      }

      context("a commit event matching the repo and branch is received") {
        modifyFixture {
          every {
            springEnv.getProperty("keel.importDeliveryConfigs.enabled", Boolean::class.java, true)
          } returns false
        }

        before {
          subject.handleCommitCreated(commitEventForAnotherRepo)
        }

        testEventIgnored()
      }
    }

    context("error scenarios") {
      before {
        setupMocks()
      }

      context("failure to retrieve delivery config") {
        modifyFixture {
          every {
            importer.import(commitEvent, "spinnaker.yml")
          } throws SystemException("oh noes!")
        }

        before {
          subject.handleCommitCreated(commitEvent)
        }

        test("a delivery config retrieval error is counted") {
          val tags = mutableListOf<Iterable<Tag>>()
          verify {
            spectator.counter(CODE_EVENT_COUNTER, capture(tags))
          }
          expectThat(tags).one {
            contains(DELIVERY_CONFIG_RETRIEVAL_ERROR.toTags())
          }
        }

        test("an event is published") {
          verify {
            eventPublisher.publishEvent(any<DeliveryConfigImportFailed>())
          }
        }
      }
    }
  }

  private fun TestContextBuilder<Fixture, Fixture>.testEventIgnored() {
    test("the event is ignored") {
      verify {
        importer wasNot called
      }
      verify {
        repository wasNot called
      }
    }
  }
}
