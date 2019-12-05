package com.netflix.spinnaker.keel.logging

import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.logging.TracingSupport.Companion.withResourceTracingContext
import com.netflix.spinnaker.keel.logging.TracingSupport.Companion.X_SPINNAKER_RESOURCE_ID
import com.netflix.spinnaker.keel.test.resource
import com.netflix.spinnaker.keel.model.Moniker
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import org.slf4j.MDC
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TracingSupportTests : JUnit5Minutests {
  val resource = resource()
  val exportable = Exportable(
    cloudProvider = "aws",
    account = "test",
    serviceAccount = "keel@spinnaker",
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

    test("injects X-SPINNAKER-RESOURCE-ID to MDC in the coroutine context from resource") {
      runBlocking {
        launch {
          withResourceTracingContext(resource) {
            expectThat(MDC.get(X_SPINNAKER_RESOURCE_ID))
              .isEqualTo(resource.id.toString())
          }
        }
      }
    }

    test("injects X-SPINNAKER-RESOURCE-ID to MDC in the coroutine context from exportable") {
      runBlocking {
        launch {
          withResourceTracingContext(exportable) {
            expectThat(MDC.get(X_SPINNAKER_RESOURCE_ID))
              .isEqualTo(exportable.toResourceId().toString())
          }
        }
      }
    }

    test("MDC context from outer scope propagates to the coroutine") {
      runBlocking {
        MDC.put("foo", "bar")
        launch {
          withResourceTracingContext(resource) {
            expectThat(MDC.get("foo"))
              .isEqualTo("bar")
          }
        }
      }
    }
  }
}
