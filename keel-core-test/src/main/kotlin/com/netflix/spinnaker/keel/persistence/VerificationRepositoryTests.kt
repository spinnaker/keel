package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.verification.VerificationContext
import com.netflix.spinnaker.keel.api.verification.VerificationRepository
import com.netflix.spinnaker.keel.api.verification.VerificationState
import com.netflix.spinnaker.keel.api.verification.VerificationStatus
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.PASSED
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.RUNNING
import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import strikt.api.expectCatching
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isSuccess
import strikt.assertions.withFirst
import java.time.Duration

abstract class VerificationRepositoryTests<IMPLEMENTATION : VerificationRepository> {

  abstract fun createSubject(): IMPLEMENTATION

  val subject: IMPLEMENTATION by lazy { createSubject() }

  open fun VerificationContext.setup() {}
  open fun VerificationContext.setupCurrentArtifactVersion() {}

  private data class DummyVerification(override val id: String) : Verification {
    override val type = "dummy"
  }

  @Test
  fun `an unknown verification has a null state`() {
    val verification = DummyVerification("1")
    val context = VerificationContext(
      deliveryConfig = DeliveryConfig(
        application = "fnord",
        name = "fnord-manifest",
        serviceAccount = "jamm@illuminati.org",
        artifacts = setOf(
          DockerArtifact(
            name = "fnord",
            deliveryConfigName = "fnord-manifest"
          )
        ),
        environments = setOf(
          Environment(
            name = "test",
            verifyWith = setOf(verification)
          )
        )
      ),
      environmentName = "test",
      version = "fnord-0.190.0-h378.eacb135"
    )

    context.setup()

    expectCatching {
      subject.getState(context, verification)
    }
      .isSuccess()
      .isNull()
  }

  @ParameterizedTest
  @EnumSource(VerificationStatus::class)
  fun `after initial creation a verification state can be retrieved`(status: VerificationStatus) {
    val verification = DummyVerification("1")
    val context = VerificationContext(
      deliveryConfig = DeliveryConfig(
        application = "fnord",
        name = "fnord-manifest",
        serviceAccount = "jamm@illuminati.org",
        artifacts = setOf(
          DockerArtifact(
            name = "fnord",
            deliveryConfigName = "fnord-manifest"
          )
        ),
        environments = setOf(
          Environment(
            name = "test",
            verifyWith = setOf(verification)
          )
        )
      ),
      environmentName = "test",
      version = "fnord-0.190.0-h378.eacb135"
    )

    context.setup()

    subject.updateState(context, verification, status)

    expectCatching {
      subject.getState(context, verification)
    }
      .isSuccess()
      .isNotNull()
      .get(VerificationState::status) isEqualTo status
  }

  @Test
  fun `different verifications are isolated from one another`() {
    val verification1 = DummyVerification("1")
    val verification2 = DummyVerification("2")
    val context = VerificationContext(
      deliveryConfig = DeliveryConfig(
        application = "fnord",
        name = "fnord-manifest",
        serviceAccount = "jamm@illuminati.org",
        artifacts = setOf(
          DockerArtifact(
            name = "fnord",
            deliveryConfigName = "fnord-manifest"
          )
        ),
        environments = setOf(
          Environment(
            name = "test",
            verifyWith = setOf(verification1, verification2)
          )
        )
      ),
      environmentName = "test",
      version = "fnord-0.190.0-h378.eacb135"
    )

    context.setup()

    subject.updateState(context, verification1, PASSED)
    subject.updateState(context, verification2, RUNNING)

    expectCatching {
      subject.getState(context, verification1)
    }
      .isSuccess()
      .isNotNull()
      .get(VerificationState::status) isEqualTo PASSED

    expectCatching {
      subject.getState(context, verification2)
    }
      .isSuccess()
      .isNotNull()
      .get(VerificationState::status) isEqualTo RUNNING
  }

  @DisplayName("selecting verifications to check")
  @Nested
  inner class NextCheckTests {
    private val minAge = Duration.ofMinutes(1)
    private val limit = 1

    private val verification = DummyVerification("1")
    private val context = VerificationContext(
      deliveryConfig = DeliveryConfig(
        application = "fnord",
        name = "fnord-manifest",
        serviceAccount = "jamm@illuminati.org",
        artifacts = setOf(
          DockerArtifact(
            name = "fnord",
            deliveryConfigName = "fnord-manifest"
          )
        ),
        environments = setOf(
          Environment(
            name = "test",
            verifyWith = setOf(verification)
          )
        )
      ),
      environmentName = "test",
      version = "fnord-0.190.0-h378.eacb135"
    )

    @Test
    fun `nothing is returned to check if there is no current artifact version for any environment`() {
      context.setup()

      expectCatching {
        subject.nextEnvironmentsForVerification(minAge, limit)
      }
        .isSuccess()
        .isEmpty()
    }

    @Test
    fun `returns the current version if it has yet to be verified`() {
      context.setup()
      context.setupCurrentArtifactVersion()

      expectCatching {
        subject.nextEnvironmentsForVerification(minAge, limit)
      }
        .isSuccess()
        .hasSize(1)
        .withFirst {
          get { version } isEqualTo context.version
          get { environmentName } isEqualTo context.environmentName
          get { deliveryConfig.name } isEqualTo context.deliveryConfig.name
        }
    }
  }
}
