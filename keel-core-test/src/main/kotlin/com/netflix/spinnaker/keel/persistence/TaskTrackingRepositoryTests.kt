package com.netflix.spinnaker.keel.persistence

import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expectThat
import strikt.assertions.first
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo

abstract class TaskTrackingRepositoryTests <T : TaskTrackingRepository> : JUnit5Minutests {

  abstract fun factory(): T

  open fun T.flush() {}

  data class Fixture<T : TaskTrackingRepository>(
    val subject: T
  )

  fun tests() = rootContext<Fixture<T>> {
    fixture {
      Fixture(subject = factory())
    }

    after { subject.flush() }

    val taskRecord1 = TaskRecord("123", "Upsert server group", com.netflix.spinnaker.keel.test.randomString())
    val taskRecord2 = TaskRecord("456", "Bake", com.netflix.spinnaker.keel.test.randomString())

    context("tasks can be stored and retrieved") {
      test("store and get a single task") {
        subject.store(taskRecord1)
        expectThat(subject.getTasks().size).isEqualTo(1)
        expectThat(subject.getTasks()).first().get {
          taskId == taskRecord1.taskId
        }
      }

      test("store and get 2 tasks") {
        subject.store(taskRecord2)
        subject.store(taskRecord1)
        expectThat(subject.getTasks().size).isEqualTo(2)
      }
    }

      test("no in-progress tasks") {
        expectThat(subject.getTasks()).isEmpty()
      }

      test("store, get and delete task") {
        subject.store(taskRecord1)
        expectThat(subject.getTasks().size).isEqualTo(1)
        subject.delete(taskRecord1.taskId)
        expectThat(subject.getTasks()).isEmpty()
      }
  }
}
