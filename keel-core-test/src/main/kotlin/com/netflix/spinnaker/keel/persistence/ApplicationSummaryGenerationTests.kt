package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus.RELEASE
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.ManualJudgementConstraint
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import java.time.Clock
import strikt.api.expect
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo

/**
 * In the artifact repository we have several methods that generate summary views of data
 * that are returned in the /application/{application} endpoint.
 * This class tests some of that data generation.
 */
abstract class ApplicationSummaryGenerationTests<T : ArtifactRepository> : JUnit5Minutests {

  abstract fun factory(clock: Clock): T

  val clock = MutableClock()

  open fun T.flush() {}

  data class Fixture<T : ArtifactRepository>(
    val subject: T
  ) {
    // the artifact built off a feature branch
    val artifact = DebianArtifact(
      name = "keeldemo",
      deliveryConfigName = "my-manifest",
      reference = "my-artifact",
      vmOptions = VirtualMachineOptions(baseOs = "bionic", regions = setOf("us-west-2")),
      statuses = setOf(RELEASE)
    )

    val environment1 = Environment("aa")
    val environment2 = Environment(
      name = "bb",
      constraints = setOf(DependsOnConstraint("test"), ManualJudgementConstraint())
    )
    val manifest = DeliveryConfig(
      name = "my-manifest",
      application = "fnord",
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(artifact),
      environments = setOf(environment1, environment2)
    )
    val version1 = "keeldemo-1.0.1-h11.1a1a1a1" // release
    val version2 = "keeldemo-1.0.2-h12.2b2b2b2" // release
//    val version3 = "keeldemo-1.0.3-h13.3c3c3c3" // release
  }

  open fun Fixture<T>.persist() {
    with(subject) {
      register(artifact)
      setOf(version1, version2).forEach {
        store(artifact, it, RELEASE)
      }
    }
    persist(manifest)
  }

  abstract fun persist(manifest: DeliveryConfig)

  fun tests() = rootContext<Fixture<T>> {
    fixture { Fixture(factory(clock)) }

    before {
      persist()
    }

    after {
      subject.flush()
    }

    context("artifact 1 skipped in env 1, mj before env 2") {
      before {
        // version 1 and 2 are approved in env 1 approved in env 1
        subject.approveVersionFor(manifest, artifact, version1, environment1.name)
        subject.approveVersionFor(manifest, artifact, version2, environment1.name)
        // only version 2 is approved in env 2
        subject.approveVersionFor(manifest, artifact, version2, environment2.name)
        // version 1 has been skipped in env 1 by version 2
        subject.markAsSkipped(manifest, artifact, version1, environment1.name, version2)
        // version 2 was successfully deployed to both envs
        subject.markAsSuccessfullyDeployedTo(manifest, artifact, version2, environment1.name)
        subject.markAsSuccessfullyDeployedTo(manifest, artifact, version2, environment2.name)
      }

      test("skipped versions don't get a pending status in the next env") {
        val summaries = subject.getEnvironmentSummaries(manifest).sortedBy { it.name }
        expect {
          that(summaries.size).isEqualTo(2)
          that(summaries[0].artifacts.first().versions.current).isEqualTo(version2)
          that(summaries[0].artifacts.first().versions.pending).isEmpty()
          that(summaries[0].artifacts.first().versions.skipped).containsExactlyInAnyOrder(version1)
          that(summaries[1].artifacts.first().versions.current).isEqualTo(version2)
          that(summaries[1].artifacts.first().versions.pending).isEmpty()
          that(summaries[1].artifacts.first().versions.skipped).containsExactlyInAnyOrder(version1)
        }
      }
    }
  }
}
