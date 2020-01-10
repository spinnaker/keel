package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.persistence.TaskRecord
import com.netflix.spinnaker.keel.persistence.TaskTrackingRepository
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.TASK_TRACKING
import org.jooq.DSLContext
import java.time.Clock

class SqlTaskTrackingRepository (
  private val jooq: DSLContext
) :TaskTrackingRepository {

  override fun store(task: TaskRecord) {
    jooq.insertInto(TASK_TRACKING)
      .set(TASK_TRACKING.RESOURCE_ID, task.resourceId)
      .set(TASK_TRACKING.TASK_ID, task.taskId)
      .set(TASK_TRACKING.TASK_NAME, task.taskName)
      .onDuplicateKeyIgnore()
      .execute()
  }

  override fun getTasks(): Set<TaskRecord> {
    return jooq
      .select(TASK_TRACKING.RESOURCE_ID, TASK_TRACKING.TASK_ID, TASK_TRACKING.TASK_NAME)
      .from(TASK_TRACKING)
      .fetch()
      .map { (resource_id, task_id, task_name) ->
        TaskRecord(resource_id, task_id, task_name)
      }
      .toSet()
  }

  override fun delete(taskId: String) {
    jooq.deleteFrom(TASK_TRACKING)
      .where(TASK_TRACKING.TASK_ID.eq(taskId))
      .execute()
  }

}
