package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.core.api.EnvironmentSummary
import com.netflix.spinnaker.keel.pause.ActuationPauser
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.ResourceStatus
import com.netflix.spinnaker.keel.persistence.ResourceSummary
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Service object that offers high-level APIs for application-related operations.
 */
@Component
class ApplicationService(
  private val pauser: ActuationPauser,
  private val repository: KeelRepository
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  fun hasManagedResources(application: String) = repository.hasManagedResources(application)

  fun getResourceSummariesFor(application: String): List<ResourceSummary> {
    var resources = repository.getSummaryByApplication(application)
    resources = resources.map { summary ->
      if (pauser.resourceIsPaused(summary.id)) {
        // we only update the status if the individual resource is paused,
        // because the application pause is reflected in the response as a top level key.
        summary.copy(status = ResourceStatus.PAUSED)
      } else {
        summary
      }
    }
    // val constraintStates = repository.constraintStateFor(application)

    return resources
  }

  fun getEnvironmentSummariesFor(application: String): List<EnvironmentSummary> =
      getFirstDeliveryConfigFor(application)
        ?.let { deliveryConfig ->
          repository.getEnvironmentSummaries(deliveryConfig)
        }
        ?: emptyList()

  private fun getFirstDeliveryConfigFor(application: String): DeliveryConfig? {
    return repository.getDeliveryConfigsByApplication(application).also {
      if (it.size > 1) {
        log.warn("Application $application has ${it.size} delivery configs. " +
          "Returning the first one: ${it.first().name}.")
      }
    }
      .firstOrNull()
  }
}
