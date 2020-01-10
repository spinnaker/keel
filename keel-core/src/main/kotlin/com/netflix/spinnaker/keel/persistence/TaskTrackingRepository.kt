package com.netflix.spinnaker.keel.persistence

interface TaskTrackingRepository {

  fun store(task: TaskRecord)
  fun getTasks(): Set<TaskRecord>
  fun delete(taskId: String)
}

data class TaskRecord(
  val taskId: String,
  val taskName: String,
  val resourceId: String
)
