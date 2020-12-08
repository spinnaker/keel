package com.netflix.spinnaker.keel.titus.verification

import com.netflix.spinnaker.keel.orca.OrcaService
import io.mockk.mockk
import io.mockk.coVerify as verify
import org.junit.jupiter.api.Test
import strikt.api.*
import strikt.assertions.*

internal class ContainerTestVerificationEvaluatorTests {

  private val orca = mockk<OrcaService>()

  @Test
  fun `starting verification launches a container job via orca`() {
    verify {
      orca.orchestrate(any(), any())
    }
  }
}
