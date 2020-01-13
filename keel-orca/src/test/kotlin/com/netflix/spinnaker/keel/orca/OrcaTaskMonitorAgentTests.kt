package com.netflix.spinnaker.keel.orca

import com.netflix.spinnaker.keel.events.TaskCreatedEvent
import com.netflix.spinnaker.keel.persistence.TaskRecord
import com.netflix.spinnaker.keel.persistence.TaskTrackingRepository
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher

internal class OrcaTaskMonitorAgentTests : JUnit5Minutests {

  data class OrcaTaskMonitorAgentFixture(
    val event: TaskCreatedEvent,
    val repository: TaskTrackingRepository = mockk(relaxUnitFun = true),
    val orcaService: OrcaService = mockk(relaxUnitFun = true),
    val publisher: ApplicationEventPublisher = mockk(relaxUnitFun = true)
  ) {
    val listner: OrcaTaskMonitorAgent = OrcaTaskMonitorAgent(repository, orcaService, publisher)
  }

  fun orcaTaskMonitorAgentTests() = rootContext<OrcaTaskMonitorAgentFixture> {
    fixture {
      OrcaTaskMonitorAgentFixture(
        event = TaskCreatedEvent(
          TaskRecord(
            taskId = "123",
            resourceId = "bla",
            taskName = "upsert server group")
        )
      )
    }

    context("a new task is being created in orca") {
      before {
          listner.onTaskEvent(event)
      }

      test("a new record is being added to the table exactly once") {
        verify(exactly = 1) { repository.store(any()) }
      }
    }
  }
}
