package com.netflix.spinnaker.keel.bakery.artifact

import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus.FINAL
import com.netflix.spinnaker.keel.api.artifacts.BaseLabel.RELEASE
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.api.artifacts.StoreType.EBS
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.bakery.BaseImageCache
import com.netflix.spinnaker.keel.clouddriver.ImageService
import com.netflix.spinnaker.keel.clouddriver.model.Image
import com.netflix.spinnaker.keel.core.NoKnownArtifactVersions
import com.netflix.spinnaker.keel.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.persistence.memory.InMemoryArtifactRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryDeliveryConfigRepository
import com.netflix.spinnaker.keel.telemetry.ArtifactCheckSkipped
import com.netflix.spinnaker.keel.test.deliveryConfig
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.Called
import io.mockk.CapturingSlot
import io.mockk.coEvery as every
import io.mockk.coVerify as verify
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID.randomUUID
import org.springframework.context.ApplicationEventPublisher
import strikt.api.Assertion
import strikt.api.Try
import strikt.api.expect
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.failed
import strikt.assertions.first
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isTrue

internal class ImageHandlerTests : JUnit5Minutests {

  internal class Fixture {
    val artifactRepository = InMemoryArtifactRepository()
    val deliveryConfigRepository = InMemoryDeliveryConfigRepository()
    val igorService = mockk<ArtifactService>()
    val baseImageCache = mockk<BaseImageCache>()
    val imageService = mockk<ImageService>()
    val publisher: ApplicationEventPublisher = mockk(relaxUnitFun = true)
    val taskLauncher = mockk<TaskLauncher>()
    val handler = ImageHandler(
      artifactRepository,
      deliveryConfigRepository,
      baseImageCache,
      igorService,
      imageService,
      publisher,
      taskLauncher,
      BakeCredentials("keel@spinnaker.io", "keel")
    )

    val artifact = DebianArtifact(
      name = "keel",
      deliveryConfigName = "delivery-config",
      vmOptions = VirtualMachineOptions(
        baseLabel = RELEASE,
        baseOs = "xenial",
        regions = setOf("us-west-2", "us-east-1"),
        storeType = EBS
      )
    )

    val image = Image(
      baseAmiVersion = "nflx-base-5.378.0-h1230.8808866",
      appVersion = "${artifact.name}-0.161.0-h63.24d0843",
      regions = artifact.vmOptions.regions
    )

    val deliveryConfig = deliveryConfig(
      configName = artifact.deliveryConfigName!!,
      artifact = artifact
    )

    lateinit var handlerResult: Assertion.Builder<Try<Unit>>
    val bakeTask = slot<List<Map<String, Any?>>>()
    val bakeTaskUser = slot<String>()
    val bakeTaskApplication = slot<String>()
    val bakeTaskArtifact = slot<List<Map<String, Any?>>>()

    fun runHandler(artifact: DeliveryArtifact) {
      if (artifact is DebianArtifact) {
        every {
          taskLauncher.submitJob(
            capture(bakeTaskUser),
            capture(bakeTaskApplication),
            any(),
            any(),
            any(),
            artifact.correlationId,
            capture(bakeTask),
            capture(bakeTaskArtifact)
          )
        } answers {
          Task(randomUUID().toString(), "baking new image for ${artifact.name}")
        }
      }

      handlerResult = expectCatching {
        handler.handle(artifact)
      }
    }
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    after {
      deliveryConfigRepository.dropAll()
      artifactRepository.dropAll()
    }

    context("the artifact is not a Debian") {
      before {
        runHandler(DockerArtifact(artifact.name))
      }

      test("nothing happens") {
        verify { imageService wasNot Called }
        verify { igorService wasNot Called }
        verify { baseImageCache wasNot Called }
        verify { taskLauncher wasNot Called }
        verify { publisher wasNot Called }
      }
    }

    context("a bake is already running for the artifact") {
      before {
        every {
          taskLauncher.correlatedTasksRunning(artifact.correlationId)
        } returns true

        runHandler(artifact)
      }

      test("an event is published") {
        verify {
          publisher.publishEvent(
            ArtifactCheckSkipped(artifact.type, artifact.name, "ActuationInProgress")
          )
        }
      }

      test("nothing else happens") {
        verify { imageService wasNot Called }
        verify { igorService wasNot Called }
        verify { baseImageCache wasNot Called }
      }
    }

    context("no bake is currently running") {
      before {
        every {
          taskLauncher.correlatedTasksRunning(artifact.correlationId)
        } returns false
      }

      context("the artifact is not registered") {
        before {
          every {
            igorService.getVersions(any(), any())
          } returns listOf(image.appVersion)

          runHandler(artifact)
        }

        test("it gets registered automatically") {
          expectThat(artifactRepository.isRegistered(artifact.name, artifact.type)).isTrue()
        }

        test("an event gets published") {
          verify {
            publisher.publishEvent(any<ArtifactRegisteredEvent>())
          }
        }
      }

      context("the artifact is registered") {
        before {
          artifactRepository.register(artifact)
          deliveryConfigRepository.store(deliveryConfig)
        }

        context("there are no known versions for the artifact in the repository or in Igor") {
          before {
            every {
              igorService.getVersions(any(), any())
            } returns emptyList()

            runHandler(artifact)
          }

          test("we do actually go check in Igor") {
            verify {
              igorService.getVersions(
                artifact.name,
                artifact.statuses.map(ArtifactStatus::toString)
              )
            }
          }

          test("the handler throws an exception") {
            handlerResult.failed().isA<NoKnownArtifactVersions>()
          }
        }

        context("there is no cached base image") {
        }

        context("we cannot get a base image from CloudDriver") {
        }

        context("the base image is cached") {
          before {
            every {
              baseImageCache.getBaseImage(artifact.vmOptions.baseOs, artifact.vmOptions.baseLabel)
            } returns image.baseAmiVersion
          }

          context("the desired version is known") {
            before {
              artifactRepository.store(artifact.name, artifact.type, image.appVersion, FINAL)
            }

            context("an AMI for the desired version and base image already exists") {
              before {
                every {
                  imageService.getLatestImageWithAllRegions(artifact.name, "test", artifact.vmOptions.regions.toList())
                } returns image

                runHandler(artifact)
              }

              test("no bake is launched") {
                verify(exactly = 0) {
                  taskLauncher.submitJob(any(), any(), any(), any(), any(), any(), any(), any<List<Map<String, Any?>>>())
                }
              }
            }

            context("an AMI for the desired version does not exist") {
              before {
                every {
                  imageService.getLatestImageWithAllRegions(artifact.name, "test", artifact.vmOptions.regions.toList())
                } returns image.copy(
                  appVersion = "${artifact.name}-0.160.0-h62.24d0843"
                )

                runHandler(artifact)
              }

              test("a bake is launched") {
                expectThat(bakeTask)
                  .isCaptured()
                  .captured
                  .hasSize(1)
                  .first()
                  .and {
                    get("type").isEqualTo("bake")
                    get("package").isEqualTo("${image.appVersion.replaceFirst('-', '_')}_all.deb")
                    get("baseOs").isEqualTo(artifact.vmOptions.baseOs)
                    get("baseLabel").isEqualTo(artifact.vmOptions.baseLabel.toString().toLowerCase())
                    get("storeType").isEqualTo(artifact.vmOptions.storeType.toString().toLowerCase())
                    get("regions").isEqualTo(artifact.vmOptions.regions)
                  }
              }

              test("authentication details are derived from the artifact's delivery config") {
                expect {
                  that(bakeTaskUser).isCaptured().captured.isEqualTo(deliveryConfig.serviceAccount)
                  that(bakeTaskApplication).isCaptured().captured.isEqualTo(deliveryConfig.application)
                }
              }

              test("the artifact details are attached") {
                expectThat(bakeTaskArtifact)
                  .isCaptured()
                  .captured
                  .hasSize(1)
                  .first()
                  .and {
                    get("name").isEqualTo(artifact.name)
                    get("version").isEqualTo(image.appVersion.removePrefix("${artifact.name}-"))
                    get("reference").isEqualTo("/${image.appVersion.replaceFirst('-', '_')}_all.deb")
                  }
              }
            }

            context("an AMI exists, but it has an older base AMI") {
              before {
                every {
                  imageService.getLatestImageWithAllRegions(artifact.name, "test", artifact.vmOptions.regions.toList())
                } returns image.copy(
                  baseAmiVersion = "nflx-base-5.377.0-h1229.3c8e02c"
                )

                runHandler(artifact)
              }

              test("a bake is launched") {
                expectThat(bakeTask)
                  .isCaptured()
                  .captured
                  .hasSize(1)
                  .first()
                  .and {
                    get("type").isEqualTo("bake")
                  }
              }
            }
          }
        }
      }
    }
  }

  fun <T : Any> Assertion.Builder<CapturingSlot<T>>.isCaptured(): Assertion.Builder<CapturingSlot<T>> =
    assertThat("captured a value", CapturingSlot<T>::isCaptured)

  val <T : Any> Assertion.Builder<CapturingSlot<T>>.captured: Assertion.Builder<T>
    get() = get { captured }
}
