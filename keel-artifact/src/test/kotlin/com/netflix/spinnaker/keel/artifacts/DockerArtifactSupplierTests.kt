package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.DOCKER
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.INCREASING_TAG
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.SEMVER_JOB_COMMIT_BY_SEMVER
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy.SEMVER_TAG
import com.netflix.spinnaker.keel.api.plugins.SupportedArtifact
import com.netflix.spinnaker.keel.api.plugins.SupportedVersioningStrategy
import com.netflix.spinnaker.keel.api.support.SpringEventPublisherBridge
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.DockerImage
import com.netflix.spinnaker.keel.test.deliveryConfig
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.coEvery as every
import io.mockk.coVerify as verify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull

internal class DockerArtifactSupplierTests : JUnit5Minutests {
  object Fixture {
    val clouddriverService: CloudDriverService = mockk(relaxUnitFun = true)
    val eventBridge: SpringEventPublisherBridge = mockk(relaxUnitFun = true)
    val deliveryConfig = deliveryConfig()
    val dockerArtifact = DockerArtifact(
      name = "fnord",
      deliveryConfigName = deliveryConfig.name,
      tagVersionStrategy = SEMVER_TAG
    )
    val versions = listOf("v1.12.1-h1188.35b8b29", "v1.12.2-h1182.8a5b962")
    val latestArtifact = PublishedArtifact(
      name = dockerArtifact.name,
      type = dockerArtifact.type,
      reference = dockerArtifact.reference,
      version = versions.last()
    )
    val dockerArtifactSupplier = DockerArtifactSupplier(eventBridge, clouddriverService)
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture }

    context("DockerArtifactSupplier") {
      before {
        every {
          clouddriverService.findDockerTagsForImage("*", dockerArtifact.name, deliveryConfig.serviceAccount)
        } returns versions
        every {
          clouddriverService.findDockerImages(account = "*", repository = latestArtifact.name, tag = latestArtifact.version)
        } returns listOf(
          DockerImage(
            account = "test",
            repository = latestArtifact.name,
            tag = latestArtifact.version,
            digest = "sha123"
          )
        )
      }

      test("supports Docker artifacts") {
        expectThat(dockerArtifactSupplier.supportedArtifact).isEqualTo(
          SupportedArtifact(DOCKER, DockerArtifact::class.java)
        )
      }

      test("supports Docker versioning strategy") {
        expectThat(dockerArtifactSupplier.supportedVersioningStrategy)
          .isEqualTo(
            SupportedVersioningStrategy(DOCKER, DockerVersioningStrategy::class.java)
          )
      }

      test("looks up latest artifact from igor") {
        val result = runBlocking {
          dockerArtifactSupplier.getLatestArtifact(deliveryConfig, dockerArtifact)
        }
        expectThat(result).isEqualTo(latestArtifact)
        verify(exactly = 1) {
          clouddriverService.findDockerTagsForImage("*", dockerArtifact.name, deliveryConfig.serviceAccount)
          clouddriverService.findDockerImages(account = "*", repository = latestArtifact.name, tag = latestArtifact.version)
        }
      }

      test("returns full version string equal to the plain version") {
        expectThat(dockerArtifactSupplier.getFullVersionString(latestArtifact))
          .isEqualTo(latestArtifact.version)
      }

      test("returns no release status for Docker artifacts") {
        expectThat(dockerArtifactSupplier.getReleaseStatus(latestArtifact))
          .isNull()
      }

      test("returns git metadata based on tag when available") {
        expectThat(dockerArtifactSupplier.getGitMetadata(latestArtifact, DockerVersioningStrategy(SEMVER_JOB_COMMIT_BY_SEMVER)))
          .isEqualTo(GitMetadata(commit = "8a5b962"))
        expectThat(dockerArtifactSupplier.getGitMetadata(latestArtifact, DockerVersioningStrategy(INCREASING_TAG)))
          .isNull()
      }

      test("returns build metadata based on tag when available") {
        expectThat(dockerArtifactSupplier.getBuildMetadata(latestArtifact, DockerVersioningStrategy(SEMVER_JOB_COMMIT_BY_SEMVER)))
          .isEqualTo(BuildMetadata(id = 1182))
        expectThat(dockerArtifactSupplier.getBuildMetadata(latestArtifact, DockerVersioningStrategy(INCREASING_TAG)))
          .isNull()
      }
    }
  }
}
