package com.netflix.spinnaker.keel.api.actuation

import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.Resource
import java.util.concurrent.CompletableFuture

interface TaskLauncher {
  suspend fun submitJob(
    resource: Resource<*>,
    description: String,
    correlationId: String,
    job: Job
  ): Task =
    submitJob(
      resource = resource,
      description = description,
      correlationId = correlationId,
      stages = listOf(job)
    )

  suspend fun submitJob(
    resource: Resource<*>,
    description: String,
    correlationId: String,
    stages: List<Job>
  ): Task

  fun submitJobAsync(
    resource: Resource<*>,
    description: String,
    correlationId: String,
    stages: List<Map<String, Any?>>
  ): CompletableFuture<Task>

  suspend fun submitJob(
    user: String,
    application: String,
    notifications: Set<NotificationConfig>,
    subject: String,
    description: String,
    correlationId: String? = null,
    stages: List<Job>,
    artifacts: List<Map<String, Any?>> = emptyList(),
    parameters: Map<String, Any> = emptyMap()
  ): Task =
    submitJob(
      user = user,
      application = application,
      notifications = notifications,
      subject = subject,
      description = description,
      correlationId = correlationId,
      stages = stages,
      type = SubjectType.CONSTRAINT,
      artifacts = artifacts,
      parameters = parameters
    )

  /**
   * Submits the list of actuation jobs specified by [stages].
   *
   * Implementations should call any registered [JobInterceptor] plugins on the list before
   * submitting the jobs for execution.
   */
  suspend fun submitJob(
    user: String,
    application: String,
    notifications: Set<NotificationConfig>,
    subject: String,
    description: String,
    correlationId: String? = null,
    stages: List<Job>,
    type: SubjectType,
    artifacts: List<Map<String, Any?>> = emptyList(),
    parameters: Map<String, Any> = emptyMap()
  ): Task

  suspend fun correlatedTasksRunning(correlationId: String): Boolean
}
