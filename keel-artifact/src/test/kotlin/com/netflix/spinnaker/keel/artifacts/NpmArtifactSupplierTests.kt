package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.artifacts.ArtifactMetadata
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus.CANDIDATE
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.NPM
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.plugins.SupportedArtifact
import com.netflix.spinnaker.keel.api.plugins.SupportedVersioningStrategy
import com.netflix.spinnaker.keel.api.support.SpringEventPublisherBridge
import com.netflix.spinnaker.keel.services.ArtifactMetadataService
import com.netflix.spinnaker.keel.test.deliveryConfig
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.coEvery as every
import io.mockk.coVerify as verify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.isEqualTo

internal class NpmArtifactSupplierTests : JUnit5Minutests {
  object Fixture {
    val artifactService: ArtifactService = mockk(relaxUnitFun = true)
    val eventBridge: SpringEventPublisherBridge = mockk(relaxUnitFun = true)
    val artifactMetadataService: ArtifactMetadataService = mockk(relaxUnitFun = true)
    val deliveryConfig = deliveryConfig()
    val npmArtifact = NpmArtifact(
      name = "fnord",
      deliveryConfigName = deliveryConfig.name,
      statuses = setOf(CANDIDATE)
    )
    val versions = listOf("1.0.0-rc", "1.0.0", "1.0.1-5", "1.0.2-h6", "1.0.3-h7-gc0c60369")
    val latestArtifact = PublishedArtifact(
      name = npmArtifact.name,
      type = npmArtifact.type,
      reference = npmArtifact.reference,
      version = "${npmArtifact.name}-${versions.last()}",
      metadata = mapOf("releaseStatus" to CANDIDATE, "buildNumber" to "1", "commitId" to "a15p0")
      )
    val npmArtifactSupplier = NpmArtifactSupplier(eventBridge, artifactService, artifactMetadataService)

    val artifactMetadata = ArtifactMetadata(
      BuildMetadata(
        id = 1,
        jobName = "job bla bla",
        uid = "1234",
        startedAt = "yesterday",
        completedAt = "today",
        jobUrl = "jenkins.com",
        number = "1"
      ),
      GitMetadata(
        commit = "a15p0",
        author = "keel-user",
        commitMessage = "this is a commit message",
        linkToCommit = "",
        projectName = "spkr",
        repoName = "keel"
      )
    )
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture }

    context("NpmArtifactSupplier") {
      before {
        every {
          artifactService.getVersions(npmArtifact.name, listOf(CANDIDATE.name), artifactType = NPM)
        } returns versions
        every {
          artifactService.getArtifact(npmArtifact.name, versions.last(), NPM)
        } returns latestArtifact
        every {
          artifactMetadataService.getArtifactMetadata("1", "a15p0")
        } returns artifactMetadata
      }

      test("supports NPM artifacts") {
        expectThat(npmArtifactSupplier.supportedArtifact).isEqualTo(
          SupportedArtifact(NPM, NpmArtifact::class.java)
        )
      }

      test("supports Netflix semver versioning strategy") {
        expectThat(npmArtifactSupplier.supportedVersioningStrategy)
          .isEqualTo(
            SupportedVersioningStrategy(NPM, NetflixSemVerVersioningStrategy::class.java)
          )
      }

      test("looks up latest artifact from igor") {
        val result = runBlocking {
          npmArtifactSupplier.getLatestArtifact(deliveryConfig, npmArtifact)
        }
        expectThat(result).isEqualTo(latestArtifact)
        verify(exactly = 1) {
          artifactService.getVersions(npmArtifact.name, listOf(CANDIDATE.name), artifactType = NPM)
          artifactService.getArtifact(npmArtifact.name, versions.last(), NPM)
        }
      }

      test("returns default full version string") {
        expectThat(npmArtifactSupplier.getFullVersionString(latestArtifact))
          .isEqualTo(latestArtifact.version)
      }

      test("returns release status based on artifact metadata") {
        expectThat(npmArtifactSupplier.getReleaseStatus(latestArtifact))
          .isEqualTo(CANDIDATE)
      }

      test("returns version display name based on Netflix semver convention") {
        expectThat(npmArtifactSupplier.getVersionDisplayName(latestArtifact))
          .isEqualTo(NetflixSemVerVersioningStrategy.getVersionDisplayName(latestArtifact))
      }

      test("returns git metadata based on Netflix semver convention") {
        val gitMeta = GitMetadata(commit = NetflixSemVerVersioningStrategy.getCommitHash(latestArtifact)!!)
        expectThat(npmArtifactSupplier.parseDefaultGitMetadata(latestArtifact, npmArtifact.versioningStrategy))
          .isEqualTo(gitMeta)
      }

      test("returns build metadata based on Netflix semver convention") {
        val buildMeta = BuildMetadata(id = NetflixSemVerVersioningStrategy.getBuildNumber(latestArtifact)!!)
        expectThat(npmArtifactSupplier.parseDefaultBuildMetadata(latestArtifact, npmArtifact.versioningStrategy))
          .isEqualTo(buildMeta)
      }

      test("returns artifact metadata based on ci provider") {
        val results = runBlocking {
          npmArtifactSupplier.getArtifactMetadata(latestArtifact)
        }
        expectThat(results)
          .isEqualTo(artifactMetadata)
      }
    }
  }
}
