package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue

data class FakeVerification(
  override val id: String ,
  override val type: String = "fake") : Verification

/**
 * Tests DependsOnConstraintEvaluator when there are verifications associated with the previous environment
 */
class DependsOnConstraintEvaluatorWithVerificationsTests : JUnit5Minutests {
  object Fixture {
    val artifact =
      DebianArtifact("fnord", vmOptions = VirtualMachineOptions(baseOs = "bionic", regions = setOf("us-west-2")))
    val verification = FakeVerification("fake-id")
    val constrainedEnvironment = Environment(
      name = "staging",
      constraints = setOf(
        DependsOnConstraint(environment = "test")
      )
    )
    val previousEnvironment = Environment(
      name = "test",
      verifyWith = setOf(verification)
    )

    val manifest = DeliveryConfig(
      name = "my-manifest",
      application = "fnord",
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(artifact),
      environments = setOf(previousEnvironment, constrainedEnvironment)
    )
  }

  val artifactRepository: ArtifactRepository = mockk(relaxUnitFun = true)

  val verificationRepository: VerificationRepository = mockk()

  val subject = DependsOnConstraintEvaluator(artifactRepository, verificationRepository, mockk())

  fun tests() = rootContext<Fixture> {
    fixture { Fixture }

    before {
      every {
        artifactRepository.wasSuccessfullyDeployedTo(
          manifest,
          artifact,
          "1.1",
          previousEnvironment.name
        )
      } returns true
    }


    context("verification succeeded") {
      before {
        every {
          verificationRepository.wasSuccessfullyVerifiedIn(
            manifest, artifact.reference, "1.1", previousEnvironment.name, verification
          )
        } returns true
      }

      test("promotion is allowed") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, constrainedEnvironment))
          .isTrue()
      }
    }

    context("verification did not succeed") {
      before {
        every {
          verificationRepository.wasSuccessfullyVerifiedIn(
            manifest, artifact.reference, "1.1", previousEnvironment.name, verification
          )
        } returns false
      }

      test("promotion is not allowed") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, constrainedEnvironment))
          .isFalse()
      }
    }
  }
}
