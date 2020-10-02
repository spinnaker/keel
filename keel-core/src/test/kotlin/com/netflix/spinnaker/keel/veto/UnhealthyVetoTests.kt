package com.netflix.spinnaker.keel.veto

import com.netflix.spinnaker.keel.events.ResourceHealthEvent
import com.netflix.spinnaker.keel.persistence.UnhealthyVetoRepository
import com.netflix.spinnaker.keel.test.locatableResource
import com.netflix.spinnaker.keel.veto.unhealthy.UnhealthyVeto
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationEventPublisher
import strikt.api.Assertion
import strikt.api.expectThat
import strikt.assertions.isNotNull

class UnhealthyVetoTests : JUnit5Minutests {
  internal class Fixture {
    val clock = MutableClock()
    val unhealthyRepository: UnhealthyVetoRepository = mockk(relaxUnitFun = true)
    val publisher: ApplicationEventPublisher = mockk(relaxUnitFun = true)
    val subject = UnhealthyVeto(unhealthyRepository, "PT5M", clock, publisher, "URL")

    val r = locatableResource()
    var result: VetoResponse? = null
    fun check() {
      result = runBlocking { subject.check(r) }
    }
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    context("resource is healthy") {
      before {
        every { unhealthyRepository.isHealthy(r.id) } returns true
      }

      test("allowed") {
        check()
        expectThat(result).isNotNull().isAllowed()
      }
    }

    context("resource is not healthy") {
      before {
        every { unhealthyRepository.isHealthy(r.id) } returns false
      }


      context("resource was marked unhealthy 1 minute ago") {
        before {
          every { unhealthyRepository.getLastAllowedTime(r.id) } returns clock.instant().minusSeconds(60)
          every { unhealthyRepository.getNoticedTime(r.id) } returns clock.instant().minusSeconds(60)
          check()
        }

        test("denied") {
          expectThat(result).isNotNull().isNotAllowed()
        }
      }

      context("resource was marked unhealthy 4 minute ago") {
        before {
          every { unhealthyRepository.getLastAllowedTime(r.id) } returns clock.instant().minusSeconds(60 * 4)
          every { unhealthyRepository.getNoticedTime(r.id) } returns clock.instant().minusSeconds(60 * 4)
          check()
        }

        test("it is vetoed") {
          expectThat(result).isNotNull().isNotAllowed()
        }
      }

      context("resource was marked unhealthy 6 minutes ago (more than the ignore duration)") {
        before {
          every { unhealthyRepository.getLastAllowedTime(r.id) } returns clock.instant().minusSeconds(60 * 6)
          check()
        }

        test("it is not vetoed") {
          expectThat(result).isNotNull().isAllowed()
        }
      }
    }

    context("marks things unhealthy") {
      before {
        subject.onResourceHealthEvent(ResourceHealthEvent(resourceId = r.id, application = "hi", healthy = false))
      }

      test("unhealthy") {
        verify(exactly = 1) { unhealthyRepository.markUnhealthy(r.id, "hi") }
      }
    }

    context("marks things healthy") {
      before {
        subject.onResourceHealthEvent(ResourceHealthEvent(resourceId = r.id, application = "hi", healthy = true))
      }

      test("healthy") {
        verify(exactly = 1) { unhealthyRepository.markHealthy(r.id) }
      }
    }
  }

  fun Assertion.Builder<VetoResponse>.isAllowed() = assertThat("is allowed") { it.allowed }
  fun Assertion.Builder<VetoResponse>.isNotAllowed() = assertThat("is not allowed") { !it.allowed }
}
