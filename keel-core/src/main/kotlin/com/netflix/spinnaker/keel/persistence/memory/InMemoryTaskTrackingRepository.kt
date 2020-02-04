package com.netflix.spinnaker.keel.persistence.memory

import com.netflix.spinnaker.keel.persistence.TaskRecord
import com.netflix.spinnaker.keel.persistence.TaskTrackingRepository

class InMemoryTaskTrackingRepository() : TaskTrackingRepository {

  private val tasks: MutableSet<TaskRecord> = mutableSetOf()
  override fun store(task: TaskRecord) {
    tasks.add(task)
  }

  override fun getTasks(): Set<TaskRecord> {
    return tasks
  }

  override fun delete(taskId: String) {
    tasks.remove(
      tasks.find { it.id == taskId }
    )
  }
}
