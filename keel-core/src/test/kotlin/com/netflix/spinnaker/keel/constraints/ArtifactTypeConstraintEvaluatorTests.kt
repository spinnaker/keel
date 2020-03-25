package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue

internal class ArtifactTypeConstraintEvaluatorTests : JUnit5Minutests {
  object Fixture {
    val manifestName = "my-manifest"
    val debian = DebianArtifact("fnord", deliveryConfigName = manifestName)
    val docker = DockerArtifact("fnord", deliveryConfigName = manifestName)

    val emptyEnv = Environment(
      name = "test"
    )
    val resource = mockk<Resource<DummyResourceSpec>>() {
      every { findAssociatedArtifact(any()) } returns debian
    }
    val lessEmptyEnv = Environment(
      name = "waffles",
      resources = setOf(resource)
    )
    val manifest = DeliveryConfig(
      name = manifestName,
      application = "fnord",
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(debian, docker),
      environments = setOf(emptyEnv, lessEmptyEnv)
    )

    val subject = ArtifactTypeConstraintEvaluator(mockk())
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture }

    test("no resources that accept artifacts means no artifacts are approved") {
      expectThat(subject.canPromote(docker, "1.1", manifest, emptyEnv))
        .isFalse()
      expectThat(subject.canPromote(debian, "1.1", manifest, emptyEnv))
        .isFalse()
    }

    test("only approves correct type of artifact") {
      expectThat(subject.canPromote(docker, "1.1", manifest, lessEmptyEnv))
        .isFalse()
      expectThat(subject.canPromote(debian, "1.1", manifest, lessEmptyEnv))
        .isTrue()
    }
  }
}
