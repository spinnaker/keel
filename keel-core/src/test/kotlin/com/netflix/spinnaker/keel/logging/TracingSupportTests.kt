package com.netflix.spinnaker.keel.logging

import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.logging.TracingSupport.Companion.X_SPINNAKER_RESOURCE_ID
import com.netflix.spinnaker.keel.logging.TracingSupport.Companion.withTracingContext
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import strikt.assertions.isNull
import java.lang.Thread.sleep
import kotlin.random.Random

class TracingSupportTests : JUnit5Minutests {
  val resource = resource()
  val exportable = Exportable(
    cloudProvider = "aws",
    account = "test",
    user = "fzlem@netflix.com",
    moniker = Moniker("keel"),
    regions = emptySet(),
    kind = resource.kind
  )

  fun tests() = rootContext {
    before {
      MDC.clear()
    }

    after {
      MDC.clear()
    }

    context("running with tracing context") {
      test("injects X-SPINNAKER-RESOURCE-ID to MDC in the coroutine context from resource") {
        runBlocking {
          withTracingContext(resource) {
            launch {
              expectThat(MDC.get(X_SPINNAKER_RESOURCE_ID))
                .isEqualTo(resource.id)
            }
          }
        }
      }

      test("injects X-SPINNAKER-RESOURCE-ID to MDC in the coroutine context from exportable") {
        runBlocking {
          withTracingContext(exportable) {
            launch {
              expectThat(MDC.get(X_SPINNAKER_RESOURCE_ID))
                .isEqualTo(exportable.toResourceId())
            }
          }
        }
      }

      test("removes X-SPINNAKER-RESOURCE-ID from MDC after block executes") {
        runBlocking {
          MDC.put("foo", "bar")
          withTracingContext(resource) {
            launch {
              expectThat(MDC.get(X_SPINNAKER_RESOURCE_ID))
                .isEqualTo(resource.id)
            }
          }.join()
          expectThat(MDC.get(X_SPINNAKER_RESOURCE_ID))
            .isNull()
          expectThat(MDC.get("foo"))
            .isEqualTo("bar")
        }
      }

      test("propagates X-SPINNAKER-RESOURCE-ID through nested coroutine contexts") {
        runBlocking {
          withTracingContext(resource) {
            launch {
              withContext(Dispatchers.IO) {
                expectThat(MDC.get(X_SPINNAKER_RESOURCE_ID))
                  .isEqualTo(resource.id)
              }
            }
          }
        }
      }

      test("does not mix up X-SPINNAKER-RESOURCE-ID between parallel coroutines") {
        val anotherResource = resource()
        val resources = (1..1000).associate { "resource-$it" to resource(id="resource-$it") }
        val coroutines = resources.map { (id, resource) ->
          GlobalScope.launch {
            withTracingContext(resource) {
              sleep(Random.nextLong(0, 50))
              println("[${Thread.currentThread().name}] X-SPINNAKER-RESOURCE-ID: ${MDC.get(X_SPINNAKER_RESOURCE_ID)}")
              expectThat(MDC.get(X_SPINNAKER_RESOURCE_ID))
                .isEqualTo(resource.id)
            }
          }
        }
        runBlocking {
          coroutines.joinAll()
        }
      }
    }
  }
}
