package com.netflix.spinnaker.keel.orca

import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.TaskStatus
import com.netflix.spinnaker.keel.api.actuation.RolloutStatus
import com.netflix.spinnaker.keel.test.configuredTestObjectMapper
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotNull
import strikt.assertions.isNull

class OrcaExecutionSummaryServiceTests {
  val mapper = configuredTestObjectMapper()
  val orcaService: OrcaService = mockk()

  val subject = OrcaExecutionSummaryService(
    orcaService,
    mapper
  )

  @Test
  fun `can read managed rollout stage`() {
    val response = javaClass.getResource("/managed-rollout-execution.json").readText()
    coEvery { orcaService.getOrchestrationExecution(any()) } returns mapper.readValue(response)

    val summary = runBlocking {
      subject.getSummary("1")
    }

    expectThat(summary.deployTargets).isNotEmpty().hasSize(2)
    expectThat(summary.deployTargets.map { it.status }.toSet()).containsExactly(RolloutStatus.SUCCEEDED)
    expectThat(summary.currentStage).isNull()
    expectThat(summary.status).isEqualTo(TaskStatus.SUCCEEDED)
  }

  @Test
  fun `can read a single region deploy stage`() {
    val response = javaClass.getResource("/single-region-deploy.json").readText()
    coEvery { orcaService.getOrchestrationExecution(any()) } returns mapper.readValue(response)

    val summary = runBlocking {
      subject.getSummary("1")
    }

    expectThat(summary.deployTargets).isNotEmpty().hasSize(1)
    expectThat(summary.deployTargets.map { it.status }.toSet()).containsExactly(RolloutStatus.SUCCEEDED)
    expectThat(summary.currentStage).isNull()
    expectThat(summary.stages).isNotEmpty().hasSize(5)
    expectThat(summary.status).isEqualTo(TaskStatus.SUCCEEDED)
  }

  @Test
  fun `can read a running single region deploy stage`() {
    val response = javaClass.getResource("/running-single-region-deploy.json").readText()
    coEvery { orcaService.getOrchestrationExecution(any()) } returns mapper.readValue(response)

    val summary = runBlocking {
      subject.getSummary("1")
    }

    expectThat(summary.deployTargets).isNotEmpty().hasSize(1)
    expectThat(summary.deployTargets.map { it.status }.toSet()).containsExactly(RolloutStatus.RUNNING)
    expectThat(summary.currentStage).isNotNull().get { type }.isEqualTo("createServerGroup")
    expectThat(summary.stages).isNotEmpty().hasSize(1)
    expectThat(summary.status).isEqualTo(TaskStatus.RUNNING)
  }
}
