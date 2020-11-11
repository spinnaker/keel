package com.netflix.spinnaker.keel.verification

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.plugins.VerificationEvaluator
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.api.verification.VerificationState
import com.netflix.spinnaker.keel.api.verification.VerificationStatus
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.FAILED
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.PASSED
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.RUNNING
import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import io.mockk.MockKVerificationScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.Serializable
import java.time.Instant.now

internal class VerificationRunnerTests {

  data class DummyVerification(val id: Serializable) : Verification {
    override val type = "dummy"
  }

  val repository = mockk<VerificationRepository>(relaxUnitFun = true)
  val evaluator1 = mockk<VerificationEvaluator<*>>(relaxUnitFun = true) {
    every { supportedVerification } returns ("dummy" to DummyVerification::class.java)
  }
  val evaluator2 = mockk<VerificationEvaluator<*>>(relaxUnitFun = true) {
    every { supportedVerification } returns ("dummy" to DummyVerification::class.java)
  }

  val subject = VerificationRunner(
    repository,
    listOf(evaluator1, evaluator2)
  )

  @Test
  fun `no-ops for an environment with no verifications`() {
    val environment = Environment(name = "test")
    val artifact = DockerArtifact(name = "fnord")
    val version = "fnord-0.190.0-h378.eacb135"

    subject.runVerificationsFor(environment, artifact, version)

    verifyNone {
      evaluator1.evaluate()
      evaluator2.evaluate()
    }
  }

  @Test
  fun `starts the first verification if none have been run yet`() {
    val environment = Environment(
      name = "test",
      verifyWith = setOf(DummyVerification(1), DummyVerification(2))
    )
    val artifact = DockerArtifact(name = "fnord")
    val version = "fnord-0.190.0-h378.eacb135"

    every {
      repository.getState(any(), any(), any(), any())
    } returns null

    subject.runVerificationsFor(environment, artifact, version)

    verify { evaluator1.evaluate() }
    verify { repository.updateState(DummyVerification(1), any(), any(), any(), RUNNING)}

    verify(exactly = 0) { evaluator2.evaluate() }
  }

  @Test
  fun `no-ops if any verification is already running`() {
    val environment = Environment(
      name = "test",
      verifyWith = setOf(DummyVerification(1), DummyVerification(2))
    )
    val artifact = DockerArtifact(name = "fnord")
    val version = "fnord-0.190.0-h378.eacb135"

    every {
      repository.getState(DummyVerification(1), any(), any(), any())
    } returns RUNNING.toState()
    every {
      repository.getState(DummyVerification(2), any(), any(), any())
    } returns null

    subject.runVerificationsFor(environment, artifact, version)

    verifyNone {
      evaluator1.evaluate()
      evaluator2.evaluate()
    }
  }

  @TestFactory
  fun `no-ops if all verifications are already complete`() =
    listOf(PASSED, FAILED).map { status->
      dynamicTest("no-ops if all verifications are already complete and the final one is $status"){
        val environment = Environment(
          name = "test",
          verifyWith = setOf(DummyVerification(1), DummyVerification(2))
        )
        val artifact = DockerArtifact(name = "fnord")
        val version = "fnord-0.190.0-h378.eacb135"

        every {
          repository.getState(DummyVerification(1), any(), any(), any())
        } returns PASSED.toState()
        every {
          repository.getState(DummyVerification(2), any(), any(), any())
        } returns status.toState()

        subject.runVerificationsFor(environment, artifact, version)

        verifyNone {
          evaluator1.evaluate()
          evaluator2.evaluate()
        }
      }
    }
}

private fun VerificationStatus.toState() =
  VerificationState(
    status = this,
    startedAt = now(),
    endedAt = if (this.complete) now() else null
  )

private fun verifyNone(verifyBlock: MockKVerificationScope.() -> Unit) {
  verifyAll(inverse = true, verifyBlock = verifyBlock)
}
