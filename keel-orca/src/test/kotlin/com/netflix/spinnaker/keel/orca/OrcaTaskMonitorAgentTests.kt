package com.netflix.spinnaker.keel.orca

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.actuation.SubjectType
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.core.api.randomUID
import com.netflix.spinnaker.keel.events.ArtifactVersionDeployed
import com.netflix.spinnaker.keel.events.ResourceTaskFailed
import com.netflix.spinnaker.keel.events.ResourceTaskSucceeded
import com.netflix.spinnaker.keel.events.TaskCreatedEvent
import com.netflix.spinnaker.keel.orca.OrcaExecutionStatus.SUCCEEDED
import com.netflix.spinnaker.keel.orca.OrcaExecutionStatus.TERMINAL
import com.netflix.spinnaker.keel.persistence.NoSuchResourceId
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.TaskRecord
import com.netflix.spinnaker.keel.persistence.TaskTrackingRepository
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.clearAllMocks
import io.mockk.coEvery as every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.springframework.context.ApplicationEventPublisher
import retrofit2.HttpException
import retrofit2.Response
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.isTrue

internal class OrcaTaskMonitorAgentTests : JUnit5Minutests {

  companion object {
    val clock: Clock = Clock.systemUTC()
    val orcaService: OrcaService = mockk(relaxed = true)
    val publisher: ApplicationEventPublisher = mockk(relaxed = true)
    val taskTrackingRepository: TaskTrackingRepository = mockk(relaxUnitFun = true)
    val resourceRepository: ResourceRepository = mockk()
    val resource: Resource<DummyResourceSpec> = resource()

    val resourceTask = TaskRecord(
      id = "123",
      subject = "${SubjectType.RESOURCE}:${resource.id}",
      name = "upsert server group"
    )

    val resourceTaskWithArtifactVersion = TaskRecord(
      id = "123",
      subject = "${SubjectType.RESOURCE}:${resource.id}",
      name = "upsert server group",
      artifactVersionUnderDeployment = "1.0.0"
    )

    val constraintTask = TaskRecord(
      id = "123",
      subject = "${SubjectType.CONSTRAINT}:${resource.id}",
      name = "canary constraint"
    )
  }

  data class OrcaTaskMonitorAgentFixture(
    var taskRecord: TaskRecord
  ) {
    val subject: OrcaTaskMonitorAgent = OrcaTaskMonitorAgent(
      taskTrackingRepository,
      resourceRepository,
      orcaService,
      publisher,
      clock
    )
  }

  fun orcaTaskMonitorAgentTests() = rootContext<OrcaTaskMonitorAgentFixture> {
    fixture {
      OrcaTaskMonitorAgentFixture(resourceTask)
    }

    context("a new orca task is being tracked") {
      before {
        every { resourceRepository.get(resource.id) } returns resource
        every { taskTrackingRepository.getTasks() } returns setOf(taskRecord)
      }

      after {
        clearAllMocks()
      }

      context("the task is currently running") {
        before {
          every {
            orcaService.getOrchestrationExecution(taskRecord.id)
          } returns executionDetailResponse(taskRecord.id)

          runBlocking {
            subject.invokeAgent()
          }
        }
  
        test("the task is ignored") {
          verify(exactly = 0) { publisher.publishEvent(any()) }
          verify(exactly = 0) { taskTrackingRepository.store(any()) }
        }
      }  

      context("the task ends successfully") {
        before {
          every {
            orcaService.getOrchestrationExecution(taskRecord.id)
          } returns executionDetailResponse(taskRecord.id, SUCCEEDED)

          runBlocking {
            subject.invokeAgent()
          }
        }

        test("a resource task succeeded event is fired") {
          verify(exactly = 1) { publisher.publishEvent(ofType<ResourceTaskSucceeded>()) }
        }

        test("the task record is deleted") {
          verify { taskTrackingRepository.delete(taskRecord.id) }
        }
      }

      test("the task ends with a failure status with no error") {
        every {
          orcaService.getOrchestrationExecution(taskRecord.id)
        } returns executionDetailResponse(taskRecord.id, TERMINAL)

        runBlocking {
          subject.invokeAgent()
        }

        verify(exactly = 1) { publisher.publishEvent(ofType<ResourceTaskFailed>()) }
        verify { taskTrackingRepository.delete(taskRecord.id) }
      }

      test("the task ends with a failure status with a general exception") {
        every {
          orcaService.getOrchestrationExecution(taskRecord.id)
        } returns executionDetailResponse(
          taskRecord.id,
          TERMINAL,
          OrcaExecutionStages(listOf(orcaContext(exception = orcaExceptions())))
        )

        runBlocking {
          subject.invokeAgent()
        }

        verify(exactly = 1) { publisher.publishEvent(ofType<ResourceTaskFailed>()) }
        verify { taskTrackingRepository.delete(taskRecord.id) }
      }

      test("the task ends with a failure status with a kato exception") {
        every {
          orcaService.getOrchestrationExecution(taskRecord.id)
        } returns executionDetailResponse(
          taskRecord.id,
          TERMINAL,
          OrcaExecutionStages(listOf(orcaContext(katoException = clouddriverExceptions())))
        )

        runBlocking {
          subject.invokeAgent()
        }

        verify(exactly = 1) { publisher.publishEvent(ofType<ResourceTaskFailed>()) }
        verify { taskTrackingRepository.delete(taskRecord.id) }
      }
    }

    context("a new orca task with an artifact under deployment is being tracked") {
      modifyFixture {
        taskRecord = resourceTaskWithArtifactVersion
      }
      
      before {
        every { resourceRepository.get(resource.id) } returns resource
        every { taskTrackingRepository.getTasks() } returns setOf(taskRecord)
      }

      after {
        clearAllMocks()
      }

      context("the task ends successfully") {
        before {
          every {
            orcaService.getOrchestrationExecution(taskRecord.id)
          } returns executionDetailResponse(taskRecord.id, SUCCEEDED)

          runBlocking {
            subject.invokeAgent()
          }
        }

        test("an artifact deployed event is fired") {
          verify { publisher.publishEvent(any<ArtifactVersionDeployed>()) }
        }
      }
    }

    context("resource not found") {
      before {
        every { resourceRepository.get(resource.id) } throws NoSuchResourceId(resource.id)
        every { taskTrackingRepository.getTasks() } returns setOf(taskRecord)
        every {
          orcaService.getOrchestrationExecution(taskRecord.id)
        } returns executionDetailResponse(taskRecord.id, SUCCEEDED)
      }

      test("task record is removed") {
        runBlocking {
          subject.invokeAgent()
        }

        verify { taskTrackingRepository.delete(taskRecord.id) }
      }
    }

    context("cannot determinate task status since orca returned a not found exception for the task id") {
      before {
        every { resourceRepository.get(resource.id) } returns resource
        every { taskTrackingRepository.getTasks() } returns setOf(taskRecord)
        every {
          orcaService.getOrchestrationExecution(taskRecord.id)
        } throws RETROFIT_NOT_FOUND
      }

      test("task record was removed from the table") {
        runBlocking {
          subject.invokeAgent()
        }

        verify(exactly = 0) { publisher.publishEvent(any()) }
        verify { taskTrackingRepository.delete(taskRecord.id) }
      }
    }

    context("cannot determinate task status since orca returned an exception for the task id") {
      before {
        every { resourceRepository.get(resource.id) } returns resource
        every { taskTrackingRepository.getTasks() } returns setOf(taskRecord)
        every {
          orcaService.getOrchestrationExecution(taskRecord.id)
        } throws Exception()
      }

      test("an exception is thrown and the task was not deleted") {
        runBlocking {
          expectThrows<Exception> {
            subject.invokeAgent()
          }
        }

        verify(exactly = 0) { publisher.publishEvent(any()) }
        expectThat(taskTrackingRepository.getTasks().contains(taskRecord)).isTrue()
      }
    }

    context("constraint events") {
      modifyFixture {
        taskRecord = constraintTask
      }

      before {
        every { taskTrackingRepository.getTasks() } returns setOf(taskRecord)
        every {
          orcaService.getOrchestrationExecution(taskRecord.id)
        } returns executionDetailResponse(taskRecord.id, SUCCEEDED)
      }

      test("do not process tasks which are constraint and not resources") {
        runBlocking {
          subject.invokeAgent()
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
        verify { taskTrackingRepository.delete(taskRecord.id) }
      }
    }
  }

  private fun executionDetailResponse(
    id: String = randomUID().toString(),
    status: OrcaExecutionStatus = OrcaExecutionStatus.RUNNING,
    execution: OrcaExecutionStages = OrcaExecutionStages(emptyList())
  ) =
    ExecutionDetailResponse(
      id = id,
      name = "fnord",
      application = "fnord",
      buildTime = clock.instant(),
      startTime = clock.instant(),
      endTime = when (status.isIncomplete()) {
        true -> null
        false -> clock.instant()
      },
      status = status,
      execution = execution
    )

  private fun orcaExceptions() =
    OrcaException(
      details = GeneralErrorsDetails(
        errors =
          listOf("Too many retries.  10 attempts have been made to bake ... its probably not going to happen."),
        stackTrace = "",
        responseBody = "",
        kind = "",
        error = ""
      ),
      exceptionType = "",
      shouldRetry = false
    )

  private fun orcaContext(
    exception: OrcaException? = null,
    katoException: List<Map<String, Any>>? = emptyList()
  ): Map<String, Any> =
    mapOf(
      "context" to
        OrcaContext(
          exception = exception,
          clouddriverException = katoException
        )
    )

  private fun clouddriverExceptions():
    List<Map<String, Any>> = (
      listOf(
        mapOf(
          "exception" to ClouddriverException(
            cause = "",
            message = "The following security groups do not exist: 'keeldemo-main-elb' in 'test' vpc-46f5a223",
            operation = "",
            type = "EXCEPTION"
          )
        )
      )
      )

  val RETROFIT_NOT_FOUND = HttpException(
    Response.error<Any>(404, "".toResponseBody("application/json".toMediaTypeOrNull()))
  )
}
