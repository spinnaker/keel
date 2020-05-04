package com.netflix.spinnaker.keel.events

import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.pause.ActuationPauser
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock

class ResourceHistoryListenerTests : JUnit5Minutests {
  object Fixture {
    val clock = Clock.systemUTC()
    val resourceRepository: ResourceRepository = mockk()
    val actuationPauser: ActuationPauser = mockk()
    val listener = ResourceHistoryListener(resourceRepository, actuationPauser, clock)
    val resource = resource()
    val resourceValidEvent = ResourceValid(resource)
    val resourceCreatedEvent = ResourceCreated(resource)
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture
    }

    context("resource event received") {
      before {
        every {
          resourceRepository.appendHistory(any() as ResourceEvent)
        } just Runs

        listener.onResourceEvent(resourceValidEvent)
      }

      test("event is persisted") {
        verify(exactly = 1) {
          resourceRepository.appendHistory(resourceValidEvent)
        }
      }
    }

    context("resource created event received and resource is paused") {
      before {
        every {
          resourceRepository.appendHistory(any() as ResourceEvent)
        } just Runs

        every {
          actuationPauser.resourceIsPaused(resource.id)
        } returns true

        every {
          resourceRepository.get(resource.id)
        } returns resource

        listener.onResourceEvent(resourceCreatedEvent)
      }

      test("resource created and paused events are persisted") {
        verify(exactly = 1) {
          resourceRepository.appendHistory(resourceCreatedEvent)
          resourceRepository.appendHistory(ofType(ResourceActuationPaused::class))
        }
      }
    }
  }
}
