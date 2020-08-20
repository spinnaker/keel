package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.igor.BuildService
import com.netflix.spinnaker.keel.api.artifacts.ArtifactMetadata
import com.netflix.spinnaker.keel.api.artifacts.BuildMetadata
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.model.Build
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import retrofit.RetrofitError
import retrofit.client.Response
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure

class ArtifactMetadataServiceTests : JUnit5Minutests {
  object Fixture {
    val buildService: BuildService = mockk()
    val artifactMetadataService = ArtifactMetadataService(buildService)
    val buildsList: List<Build> = listOf(
      Build(
        number = 1,
        name = "job bla bla",
        id = "1234",
        building = false,
        fullDisplayName = "job bla bla",
        url = "jenkins.com",
        properties = mapOf(
          "startedAt" to "yesterday",
          "completedAt" to "today",
          "projectKey" to "spkr",
          "repoSlug" to "keel",
          "author" to "keel-user",
          "message" to "this is a commit message"
        )
      )
    )
  }

  fun tests() = rootContext<Fixture> {
    context("get artifact metadata") {
      fixture { Fixture }


      context("with valid commit id and build number") {
        before {
          coEvery {
            buildService.getArtifactMetadata(any(), any())
          } returns buildsList
        }

        test("succeeds and converted the results correctly") {
          val results = runBlocking {
            artifactMetadataService.getArtifactMetadata("1", "a15p0")
          }

          expectThat(results).isEqualTo(
            ArtifactMetadata(
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
          )
        }
      }

      context("with valid commit id and build number") {
        before {
          coEvery {
            buildService.getArtifactMetadata(any(), any())
          } returns buildsList
        }

        test("succeeds and converted the results correctly") {
          val results = runBlocking {
            artifactMetadataService.getArtifactMetadata("1", "a15p0")
          }

          expectThat(results).isEqualTo(
            ArtifactMetadata(
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
          )
        }
      }

      context("return an empty results from the CI provider") {
        before {
          coEvery {
            buildService.getArtifactMetadata(any(), any())
          } returns listOf()
        }

        test("return null") {
          val results = runBlocking {
            artifactMetadataService.getArtifactMetadata("1", "a15p0")
          }

          expectThat(results).isEqualTo(null)
        }
      }

      context("with HTTP error coming from igor") {
        val retrofitError = RetrofitError.httpError(
          "http://igor",
          Response("http://igor", 404, "not found", emptyList(), null),
          null, null
        )

        before {
          coEvery {
            buildService.getArtifactMetadata(any(), any())
          } throws retrofitError
        }

        test("show http error") {
          expectCatching {
            artifactMetadataService.getArtifactMetadata("1", "a15p0")
          }
            .isFailure()
            .isEqualTo(retrofitError)
        }
      }
    }
  }
}
