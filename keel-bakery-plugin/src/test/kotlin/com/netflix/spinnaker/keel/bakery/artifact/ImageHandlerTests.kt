package com.netflix.spinnaker.keel.bakery.artifact

import com.netflix.spinnaker.igor.ArtifactService
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
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.ImageService
import com.netflix.spinnaker.keel.clouddriver.model.Image
import com.netflix.spinnaker.keel.core.NoKnownArtifactVersions
import com.netflix.spinnaker.keel.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.orca.TaskRefResponse
import com.netflix.spinnaker.keel.persistence.memory.InMemoryArtifactRepository
import com.netflix.spinnaker.keel.telemetry.ArtifactCheckSkipped
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
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.failed
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isTrue

internal class ImageHandlerTests : JUnit5Minutests {

  internal class Fixture {
    val artifactRepository = InMemoryArtifactRepository()
    val theCloudDriver = mockk<CloudDriverService>()
    val igorService = mockk<ArtifactService>()
    val baseImageCache = mockk<BaseImageCache>()
    val imageService = mockk<ImageService>()
    val publisher: ApplicationEventPublisher = mockk(relaxUnitFun = true)
    val taskLauncher = mockk<TaskLauncher>()
    val handler = ImageHandler(
      artifactRepository,
      baseImageCache,
      theCloudDriver,
      igorService,
      imageService,
      publisher,
      taskLauncher
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

    lateinit var handlerResult: Assertion.Builder<Try<Unit>>
    val bakeTask: CapturingSlot<List<Map<String, Any?>>> = slot<List<Map<String, Any?>>>()

    fun runHandler(artifact: DeliveryArtifact) {
      if (artifact is DebianArtifact) {
        every {
          taskLauncher.submitJob(
            any(),
            any(),
            any(),
            any(),
            any(),
            artifact.correlationId,
            capture(bakeTask),
            any<List<Map<String, Any?>>>()
          )
        }
      }

      handlerResult = expectCatching {
        handler.handle(artifact)
      }
    }
  }

  // TODO: keep getting the same diff even after baking (bake failed?)
  // TODO: already an image in more regions (still no diff)
  // TODO: latest image has older app version - launch bake
  // TODO: latest image has older base image - launch bake
  // TODO: already an image but not in all regions (rebake?)
  // TODO: can't find artifact version
  // TODO: can't find artifact version with right status
  // TODO: no cached base image
  // TODO: can't find base image
  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    after { artifactRepository.dropAll() }

    context("the artifact is not a Debian") {
      before {
        runHandler(DockerArtifact(artifact.name))
      }

      test("nothing happens") {
        verify { imageService wasNot Called }
        verify { theCloudDriver wasNot Called }
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
        verify { theCloudDriver wasNot Called }
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
            }
          }
        }
      }
    }

    /**
    context("resolving desired and current state") {
    context("CloudDriver has an image for the base AMI") {
    before {
    artifactRepository.register(artifact)
    artifactRepository.store(artifact.name, artifact.type, image.appVersion, FINAL)

    every {
    baseImageCache.getBaseImage(artifact.vmOptions.baseOs, artifact.vmOptions.baseLabel)
    } returns "xenialbase-x86_64-201904291721-ebs"

    every { theCloudDriver.namedImages("keel@spinnaker", "xenialbase-x86_64-201904291721-ebs", "test") } returns
    listOf(
    NamedImage(
    imageName = "xenialbase-x86_64-201904291721-ebs",
    attributes = mapOf(
    "virtualizationType" to "paravirtual",
    "creationDate" to "2019-04-29T18:11:45.000Z"
    ),
    tagsByImageId = mapOf(
    "ami-0c3f1dc20535ef3b7" to mapOf(
    "base_ami_version" to "nflx-base-5.378.0-h1230.8808866",
    "creation_time" to "2019-04-29 17:53:18 UTC",
    "creator" to "builds",
    "base_ami_flavor" to "xenial",
    "build_host" to "https://opseng.builds.test.netflix.net/"
    ),
    "ami-0c86c73e07f5df756" to mapOf(
    "creation_time" to "2019-04-29 17:53:18 UTC",
    "build_host" to "https://opseng.builds.test.netflix.net/",
    "base_ami_flavor" to "xenial",
    "base_ami_version" to "nflx-base-5.378.0-h1230.8808866",
    "creator" to "builds"
    ),
    "ami-05f25743c025c5a11" to mapOf(
    "base_ami_version" to "nflx-base-5.378.0-h1230.8808866",
    "build_host" to "https://opseng.builds.test.netflix.net/",
    "creation_time" to "2019-04-29 17:53:18 UTC",
    "creator" to "builds",
    "base_ami_flavor" to "xenial"
    ),
    "ami-04772f06ffdb0bc68" to mapOf(
    "base_ami_flavor" to "xenial",
    "creator" to "builds",
    "creation_time" to "2019-04-29 17:53:18 UTC",
    "build_host" to "https://opseng.builds.test.netflix.net/",
    "base_ami_version" to "nflx-base-5.378.0-h1230.8808866"
    )
    ),
    accounts = setOf("test"),
    amis = mapOf(
    "eu-west-1" to listOf("ami-04772f06ffdb0bc68"),
    "us-east-1" to listOf("ami-0c3f1dc20535ef3b7"),
    "us-west-1" to listOf("ami-0c86c73e07f5df756"),
    "us-west-2" to listOf("ami-05f25743c025c5a11")
    )
    )
    )

    every { imageService.getLatestImage("keel", "test") } returns image
    }

    test("desired state composes application and base image versions") {
    val desired = runBlocking {
    handler.desired(resource)
    }
    expectThat(desired).isEqualTo(image)
    }
    }

    context("there are no known versions of the artifact") {
    before {
    artifactRepository.register(artifact)
    every {
    baseImageCache.getBaseImage(artifact.vmOptions.baseOs, artifact.vmOptions.baseLabel)
    } returns "xenialbase-x86_64-201904291721-ebs"
    every {
    igorService.getVersions(artifact.name, any())
    } returns emptyList()
    }

    test("an exception is thrown") {
    expectThrows<NoKnownArtifactVersions> { handler.desired(resource) }
    }
    }

    context("there is only a version with the wrong status") {
    before {
    artifactRepository.register(artifact)
    artifactRepository.store(artifact.name, artifact.type, image.appVersion, FINAL)
    every {
    baseImageCache.getBaseImage(artifact.vmOptions.baseOs, artifact.vmOptions.baseLabel)
    } returns "xenialbase-x86_64-201904291721-ebs"
    every {
    igorService.getVersions("keel", any())
    } returns listOf()
    }

    test("an exception is thrown") {
    expectThrows<NoKnownArtifactVersions> { handler.desired(resourceOnlySnapshot) }
    }
    }

    context("there is no cached base image") {
    before {
    artifactRepository.register(artifact)
    artifactRepository.store(artifact.name, artifact.type, image.appVersion, FINAL)

    every {
    baseImageCache.getBaseImage(artifact.vmOptions.baseOs, artifact.vmOptions.baseLabel)
    } throws UnknownBaseImage(artifact.vmOptions.baseOs, artifact.vmOptions.baseLabel)
    }

    test("the exception is propagated") {
    expectThrows<UnknownBaseImage> { handler.desired(resource) }
    }
    }

    context("clouddriver can't find the base AMI") {
    before {
    artifactRepository.register(artifact)
    artifactRepository.store(artifact.name, artifact.type, image.appVersion, FINAL)

    every {
    baseImageCache.getBaseImage(artifact.vmOptions.baseOs, artifact.vmOptions.baseLabel)
    } returns "xenialbase-x86_64-201904291721-ebs"

    every {
    theCloudDriver.namedImages("keel@spinnaker", "xenialbase-x86_64-201904291721-ebs", "test")
    } returns emptyList()
    }

    test("an exception is thrown") {
    expectThrows<BaseAmiNotFound> { handler.desired(resource) }
    }
    }

    context("the image already exists in more regions than desired") {
    before {
    every {
    imageService.getLatestImageWithAllRegions("keel", "test", image.regions.toList())
    } returns image.copy(regions = image.regions + "eu-west-1")
    }
    test("current should filter the undesireable regions out of the image") {
    runBlocking {
    expectThat(handler.current(resource)!!.regions).isEqualTo(artifact.vmOptions.regions)
    }
    }
    }
    }

    context("baking a new AMI") {
    test("artifact is attached to the trigger") {
    val request = slot<OrchestrationRequest>()
    every { orcaService.orchestrate("keel@spinnaker", capture(request)) } returns randomTaskRef()

    runBlocking {
    handler.upsert(resource, DefaultResourceDiff(image, null))
    }

    expectThat(request.captured.trigger.artifacts)
    .hasSize(1)
    }

    test("the full debian name is specified based on naming convention when we create a bake task") {
    val request = slot<OrchestrationRequest>()
    every { orcaService.orchestrate("keel@spinnaker", capture(request)) } returns randomTaskRef()

    runBlocking {
    handler.upsert(resource, DefaultResourceDiff(image, null))
    }

    expectThat(request.captured.job.first())
    .hasEntry("package", "keel_0.161.0-h63.24d0843_all.deb")
    }
    }
     **/
  }

  fun <T : Any> Assertion.Builder<CapturingSlot<T>>.isCaptured(): Assertion.Builder<CapturingSlot<T>> =
    assertThat("captured a value", CapturingSlot<T>::isCaptured)

  val <T : Any> Assertion.Builder<CapturingSlot<T>>.captured: Assertion.Builder<T>
    get() = get { captured }

  private fun randomTaskRef() = TaskRefResponse(randomUUID().toString())
}
