package com.netflix.spinnaker.keel.titus.verification

import com.netflix.spinnaker.keel.api.Verification
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.plugins.VerificationEvaluator
import com.netflix.spinnaker.keel.api.verification.VerificationContext
import com.netflix.spinnaker.keel.api.verification.VerificationStatus
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.FAILED
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.PASSED
import com.netflix.spinnaker.keel.api.verification.VerificationStatus.RUNNING
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.titus.batch.ContainerJobConfig
import com.netflix.spinnaker.keel.titus.batch.ImageVersionReference.TAG
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

@Component
class ContainerTestVerificationEvaluator(
  private val orca: OrcaService,
  private val taskLauncher: TaskLauncher
) : VerificationEvaluator<ContainerTestVerification> {

  override val supportedVerification: Pair<String, Class<ContainerTestVerification>> =
    "container-tests" to ContainerTestVerification::class.java

  override fun evaluate(
    context: VerificationContext,
    verification: Verification,
    metadata: Map<String, Any?>
  ): VerificationStatus {
    val taskId = metadata["taskId"]
    require(taskId is String) {
      "No task id found in previous verification state"
    }

    return runBlocking {
      withContext(IO) {
        orca.getOrchestrationExecution(taskId)
      }
        .let { response ->
          when {
            response.status.isSuccess() -> PASSED
            response.status.isIncomplete() -> RUNNING
            else -> FAILED
          }
        }
    }
  }

  override fun start(context: VerificationContext, verification: Verification): Map<String, Any?> {
    require(verification is ContainerTestVerification) {
      "Expected a ${ContainerTestVerification::class.simpleName} but received a ${verification.javaClass.simpleName}"
    }

    val containerJobConfig = ContainerJobConfig(
      name = "TODO: value",
      application = context.deliveryConfig.application,
      account = "TODO: value",
      region = "TODO: value",
      organization = "TODO: value",
      repository = verification.repository,
      serviceAccount = context.deliveryConfig.serviceAccount,
      credentials = "TODO: value",
      registry = "TODO: value",
      type = TAG,
      versionIdentifier = verification.tag
    )

    return runBlocking {
      withContext(IO) {
        taskLauncher.submitJob(
          subject = "container integration test",
          description = "testing running a container",
          correlationId = "keel-run-container-test",
          user = context.deliveryConfig.serviceAccount,
          application = context.deliveryConfig.application,
          notifications = emptySet(),
          stages = listOf(containerJobConfig.createRunJobStage())
        )
      }
        .let { task ->
          mapOf("taskId" to task.id)
        }
    }
  }
}
