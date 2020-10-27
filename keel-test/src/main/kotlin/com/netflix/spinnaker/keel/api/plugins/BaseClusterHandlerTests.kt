package com.netflix.spinnaker.keel.api.plugins

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceDiff
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.support.EventPublisher
import com.netflix.spinnaker.keel.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import java.time.Clock

abstract class BaseClusterHandlerTests<
  SPEC: ResourceSpec, // spec type
  RESOLVED: Any, // resolved type
  HANDLER : BaseClusterHandler<SPEC, RESOLVED>
  > : JUnit5Minutests {

  abstract fun createSpyHandler(
    resolvers: List<Resolver<*>>,
    clock: Clock,
    eventPublisher: EventPublisher): HANDLER
  abstract fun getResourceOneRegion(): Resource<SPEC>
  abstract fun getRegions(resource: Resource<SPEC>): List<String>
  abstract fun getResourceTwoRegions(): Resource<SPEC>
  abstract fun getDiffInMoreThanEnabled(): ResourceDiff<Map<String, RESOLVED>>
  abstract fun getDiffOnlyInEnabled(): ResourceDiff<Map<String, RESOLVED>>

  val clock: Clock = MutableClock()
  val eventPublisher: EventPublisher = mockk()
  val resolvers: List<Resolver<*>> = emptyList()

  data class Fixture<SPEC: ResourceSpec, RESOLVED: Any, HANDLER : BaseClusterHandler<SPEC, RESOLVED>>(
    val handler: HANDLER
  )

  fun test() = rootContext<Fixture<SPEC, RESOLVED, HANDLER>> {
    fixture{
      Fixture(
        // create spy handler here so we can test only base cluster logic, not handler
        // specific logic
        createSpyHandler(resolvers = resolvers, clock = clock, eventPublisher = eventPublisher),
      )
    }

    after {
      clearAllMocks()
    }

    context("testing whether handler will take action") {
      context("diff in more then just enabled") {
        test("handler will take action") {
          val resource = getResourceOneRegion()
          val diff = getDiffInMoreThanEnabled()
          val response = runBlocking { handler.willTakeAction(resource, diff) }
          expectThat(response.willAct).isTrue()
        }
      }

      context("diff only in enabled") {
        context("all regions healthy") {
          before {
            every { handler.getUnhealthyRegionsForActiveServerGroup(any()) } returns listOf()
          }
          test("handler will take action") {
            val resource = getResourceOneRegion()
            val diff = getDiffOnlyInEnabled()
            val response = runBlocking { handler.willTakeAction(resource, diff) }
            expectThat(response.willAct).isTrue()
          }
        }

        context("one region unhealthy") {
          before {
            every { handler.getUnhealthyRegionsForActiveServerGroup(any()) } returns getRegions(getResourceOneRegion())
          }
          test("handler will not action") {
            val resource = getResourceOneRegion()
            val diff = getDiffOnlyInEnabled()
            val response = runBlocking { handler.willTakeAction(resource, diff) }
            expectThat(response.willAct).isFalse()
          }
        }
      }
    }
  }
}



