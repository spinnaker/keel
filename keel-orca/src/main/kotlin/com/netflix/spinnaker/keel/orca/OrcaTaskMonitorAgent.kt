package com.netflix.spinnaker.keel.orca

import com.fasterxml.jackson.module.kotlin.convertValue
import com.netflix.spinnaker.keel.api.actuation.SubjectType.RESOURCE
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.core.api.DEFAULT_SERVICE_ACCOUNT
import com.netflix.spinnaker.keel.events.ArtifactVersionDeployed
import com.netflix.spinnaker.keel.events.ResourceTaskFailed
import com.netflix.spinnaker.keel.events.ResourceTaskSucceeded
import com.netflix.spinnaker.keel.events.TaskCreatedEvent
import com.netflix.spinnaker.keel.persistence.NoSuchResourceId
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.TaskRecord
import com.netflix.spinnaker.keel.persistence.TaskTrackingRepository
import com.netflix.spinnaker.keel.retrofit.isNotFound
import com.netflix.spinnaker.keel.scheduled.ScheduledAgent
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import retrofit2.HttpException

@Component
class OrcaTaskMonitorAgent(
  private val taskTrackingRepository: TaskTrackingRepository,
  private val resourceRepository: ResourceRepository,
  private val orcaService: OrcaService,
  private val publisher: ApplicationEventPublisher,
  private val clock: Clock
) : ScheduledAgent {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override val lockTimeoutSeconds = TimeUnit.MINUTES.toSeconds(1)

  private val mapper = configuredObjectMapper()

  @EventListener(TaskCreatedEvent::class)
  fun onTaskEvent(event: TaskCreatedEvent) {
    taskTrackingRepository.store(event.taskRecord)
  }

  // 1. Get active tasks from task tracking table
  // 2. For each task, call orca and ask for details
  // 3. For each completed task, will emit an event for success/failure and delete from the table
  override suspend fun invokeAgent() {
    coroutineScope {
      taskTrackingRepository.getTasks()
        .associate {
          it to
            async {
              try {
                orcaService.getOrchestrationExecution(it.id, DEFAULT_SERVICE_ACCOUNT)
              } catch (e: HttpException) {
                when (e.isNotFound) {
                  true -> {
                    log.warn(
                      "Exception ${e.message} has caught while calling orca to fetch status for execution id: ${it.id}" +
                        " Possible reason: orca is saving info for 2000 tasks/app and this task is older.",
                      e
                    )
                    // when we get not found exception from orca, we shouldn't try to get the status anymore
                    taskTrackingRepository.delete(it.id)
                  }
                  else -> throw e
                }
                null
              }
            }.await()
        }
        .filterValues { it != null && it.status.isComplete() }
        .map { (taskRecord, executionDetails) ->
          if (executionDetails != null) {
            // only resource events are currently supported
            if (taskRecord.subject.startsWith(RESOURCE.toString())) {
              val resourceId = taskRecord.subject.substringAfter(":")
              try {
                if (executionDetails.status.isSuccess()) {
                  handleTaskSuccess(resourceId, executionDetails, taskRecord)
                } else {
                  handleTaskFailure(resourceId, executionDetails)
                }
              } catch (e: NoSuchResourceId) {
                log.warn("No resource found for id $taskRecord")
              }
            }
            taskTrackingRepository.delete(executionDetails.id)
          }
        }
    }
  }

  private fun handleTaskSuccess(resourceId: String, executionDetails: ExecutionDetailResponse, taskRecord: TaskRecord) {
    publisher.publishEvent(
      ResourceTaskSucceeded(
        resourceRepository.get(resourceId), listOf(Task(executionDetails.id, executionDetails.name)), clock
      )
    )

    if (taskRecord.artifactVersionUnderDeployment != null) {
      log.debug("Successful task ${taskRecord.id} deployed artifact version ${taskRecord.artifactVersionUnderDeployment} " +
        "to resource ${resourceId}. Notifying artifact version deployed.")
      publisher.publishEvent(
        ArtifactVersionDeployed(
          resourceId = resourceId,
          artifactVersion = taskRecord.artifactVersionUnderDeployment!!
        )
      )
    }
  }

  private fun handleTaskFailure(resourceId: String, executionDetails: ExecutionDetailResponse) {
    publisher.publishEvent(
      ResourceTaskFailed(
        resourceRepository.get(resourceId),
        executionDetails.execution.stages.getFailureMessage()
          ?: "",
        listOf(Task(executionDetails.id, executionDetails.name)), clock
      )
    )
  }

  // get the exception - can be either general orca exception or kato specific
  private fun List<Map<String, Any>>?.getFailureMessage(): String? {

    this?.forEach { it ->
      val context: OrcaContext? = it["context"]?.let { mapper.convertValue(it) }

      // find the first exception and return
      if (context?.exception != null) {
        return context.exception.details.errors.joinToString(",")
      }

      if (context?.clouddriverException != null) {
        val clouddriverError: ClouddriverException? = context.clouddriverException.first()["exception"]?.let { mapper.convertValue(it) }
        return clouddriverError?.message
      }
    }

    return null
  }
}
