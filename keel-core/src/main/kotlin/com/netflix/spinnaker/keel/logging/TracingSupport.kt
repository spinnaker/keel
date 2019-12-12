package com.netflix.spinnaker.keel.logging

import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.id
import kotlinx.coroutines.CoroutineScope
import org.slf4j.MDC
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext

/**
 * Support for tracing resources in log statements via MDC in coroutines.
 */
class TracingSupport {
  companion object {
    const val X_SPINNAKER_RESOURCE_ID = "X-SPINNAKER-RESOURCE-ID"

    suspend fun <T : ResourceSpec, R> withResourceTracingContext(
      resource: Resource<T>,
      block: suspend CoroutineScope.() -> R
    ): R {
      return withResourceTracingContext(resource.id, block)
    }

    suspend fun <R> withResourceTracingContext(
      exportable: Exportable,
      block: suspend CoroutineScope.() -> R
    ): R {
      return withResourceTracingContext(exportable.toResourceId(), block)
    }

    private suspend fun <R> withResourceTracingContext(
      resourceId: ResourceId,
      block: suspend CoroutineScope.() -> R
    ): R {
      try {
        MDC.put(X_SPINNAKER_RESOURCE_ID, resourceId.toString())
        return withContext(MDCContext(), block)
      } finally {
        MDC.remove(X_SPINNAKER_RESOURCE_ID)
      }
    }
  }
}
