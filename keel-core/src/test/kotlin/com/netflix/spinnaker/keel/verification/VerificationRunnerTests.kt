package com.netflix.spinnaker.keel.verification

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.plugins.VerificationEvaluator
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.api.verification.VerificationState
import com.netflix.spinnaker.keel.api.verification.VerificationStatus
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.PASSED
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.RUNNING
import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import io.mockk.MockKVerificationScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.Serializable
import java.time.Instant.now

internal class VerificationRunnerTests {

  data class DummyVerification(val id: Serializable) : Verification {
    override val type = "dummy"
  }

  val repository = mockk<VerificationRepository>(relaxUnitFun = true)
  val evaluator = mockk<VerificationEvaluator<DummyVerification>>(relaxUnitFun = true) {
    every { supportedVerification } returns ("dummy" to DummyVerification::class.java)
  }

  val subject = VerificationRunner(
    repository,
    listOf(evaluator)
  )

  @Test
  fun `no-ops for an environment with no verifications`() {
    val environment = Environment(name = "test")
    val artifact = DockerArtifact(name = "fnord")
    val version = "fnord-0.190.0-h378.eacb135"

    subject.runVerificationsFor(environment, artifact, version)

    verify(exactly = 0) {
      evaluator.start(any())
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

    verify { evaluator.start(DummyVerification(1)) }
    verify { repository.updateState(DummyVerification(1), any(), any(), any(), RUNNING) }
  }

  @Test
  fun `no-ops if any verification was already running and has yet to complete`() {
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

    every {
      evaluator.evaluate()
    } returns RUNNING

    subject.runVerificationsFor(environment, artifact, version)

    verify {
      evaluator.evaluate()
    }
    verify(exactly = 0) {
      evaluator.start(any())
    }
  }

  @ParameterizedTest(
    name = "continues to the next if any verification was already running and has now completed with {0}"
  )
  @EnumSource(
    value = VerificationStatus::class,
    names = ["PASSED", "FAILED"]
  )
  fun `continues to the next if any verification was already running and has now completed`(status: VerificationStatus) {
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

    every {
      evaluator.evaluate()
    } returns status

    subject.runVerificationsFor(environment, artifact, version)

    verify {
      repository.updateState(DummyVerification(1), any(), any(), any(), status)
    }
    verify {
      evaluator.start(DummyVerification(2))
    }
    verify {
      repository.updateState(DummyVerification(2), any(), any(), any(), RUNNING)
    }
  }

  @ParameterizedTest(
    name = "no-ops if all verifications are already complete and the final one is {0}"
  )
  @EnumSource(
    value = VerificationStatus::class,
    names = ["PASSED", "FAILED"]
  )
  fun `no-ops if all verifications are already complete`(status: VerificationStatus) {
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

    verify(exactly = 0) {
      evaluator.start(any())
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
