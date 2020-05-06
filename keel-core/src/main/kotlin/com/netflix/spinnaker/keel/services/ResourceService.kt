package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.Monikered
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.SimpleRegionSpec
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.core.api.ResourceArtifactSummary
import com.netflix.spinnaker.keel.core.api.ResourceSummary
import com.netflix.spinnaker.keel.events.ApplicationActuationPaused
import com.netflix.spinnaker.keel.events.ApplicationActuationResumed
import com.netflix.spinnaker.keel.events.PersistentEvent
import com.netflix.spinnaker.keel.events.ResourceActuationLaunched
import com.netflix.spinnaker.keel.events.ResourceActuationPaused
import com.netflix.spinnaker.keel.events.ResourceActuationResumed
import com.netflix.spinnaker.keel.events.ResourceActuationVetoed
import com.netflix.spinnaker.keel.events.ResourceCheckError
import com.netflix.spinnaker.keel.events.ResourceCheckUnresolvable
import com.netflix.spinnaker.keel.events.ResourceCreated
import com.netflix.spinnaker.keel.events.ResourceDeltaDetected
import com.netflix.spinnaker.keel.events.ResourceDeltaResolved
import com.netflix.spinnaker.keel.events.ResourceMissing
import com.netflix.spinnaker.keel.events.ResourceTaskFailed
import com.netflix.spinnaker.keel.events.ResourceTaskSucceeded
import com.netflix.spinnaker.keel.events.ResourceValid
import com.netflix.spinnaker.keel.pause.ActuationPauser
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.NoSuchDeliveryConfigException
import com.netflix.spinnaker.keel.persistence.ResourceRepository.Companion.DEFAULT_MAX_EVENTS
import com.netflix.spinnaker.keel.persistence.ResourceStatus
import com.netflix.spinnaker.keel.persistence.ResourceStatus.ACTUATING
import com.netflix.spinnaker.keel.persistence.ResourceStatus.CREATED
import com.netflix.spinnaker.keel.persistence.ResourceStatus.CURRENTLY_UNRESOLVABLE
import com.netflix.spinnaker.keel.persistence.ResourceStatus.DIFF
import com.netflix.spinnaker.keel.persistence.ResourceStatus.ERROR
import com.netflix.spinnaker.keel.persistence.ResourceStatus.HAPPY
import com.netflix.spinnaker.keel.persistence.ResourceStatus.PAUSED
import com.netflix.spinnaker.keel.persistence.ResourceStatus.RESUMED
import com.netflix.spinnaker.keel.persistence.ResourceStatus.UNHAPPY
import com.netflix.spinnaker.keel.persistence.ResourceStatus.UNKNOWN
import com.netflix.spinnaker.keel.persistence.ResourceStatus.VETOED
import org.springframework.stereotype.Component

/**
 * Service object that offers high-level APIs for resource-related operations.
 */
@Component
class ResourceService(
  private val repository: KeelRepository,
  private val actuationPauser: ActuationPauser
) {

  /**
   * Returns the history of events associated with the specified resource from the database, plus any applicable
   * application-level pause/resume events.
   */
  fun getEnrichedEventHistory(id: String, limit: Int = DEFAULT_MAX_EVENTS): List<PersistentEvent> {
    val resource = repository.getResource(id)
    return repository.resourceEventHistory(id, limit)
      .let { events ->
        actuationPauser.addApplicationActuationEvents(events, resource)
      }
  }

  /**
   * Returns the status of the specified resource by first checking whether or not it or the parent application are
   * paused, then looking into the last few events in the resource's history.
   */
  fun getStatus(id: String): ResourceStatus {
    // For the PAUSED status, we look at the `paused` table as opposed to events, since these records
    // persist even when a delivery config/resource (and associated events) have been deleted. We do
    // this so we don't inadvertently start actuating on a resource that had been previously paused,
    // without explicit action from the user to resume.
    if (actuationPauser.isPaused(id)) {
      return PAUSED
    }

    val history = getEnrichedEventHistory(id, 10)
    return when {
      history.isHappy() -> HAPPY
      history.isUnhappy() -> UNHAPPY
      history.isDiff() -> DIFF
      history.isActuating() -> ACTUATING
      history.isError() -> ERROR
      history.isCreated() -> CREATED
      history.isVetoed() -> VETOED
      history.isResumed() -> RESUMED
      history.isCurrentlyUnresolvable() -> CURRENTLY_UNRESOLVABLE
      else -> UNKNOWN
    }
  }

  private fun List<PersistentEvent>.isHappy(): Boolean {
    return first() is ResourceValid || first() is ResourceDeltaResolved
  }

  private fun List<PersistentEvent>.isActuating(): Boolean {
    return first() is ResourceActuationLaunched || first() is ResourceTaskSucceeded ||
      // we might want to move ResourceTaskFailed to isError later on
      first() is ResourceTaskFailed
  }

  private fun List<PersistentEvent>.isError(): Boolean {
    return first() is ResourceCheckError
  }

  private fun List<PersistentEvent>.isCreated(): Boolean {
    return first() is ResourceCreated
  }

  private fun List<PersistentEvent>.isDiff(): Boolean {
    return first() is ResourceDeltaDetected || first() is ResourceMissing
  }

  private fun List<PersistentEvent>.isPaused(): Boolean {
    val appPaused = firstOrNull { it is ApplicationActuationPaused }?.timestamp
    val appResumed = firstOrNull { it is ApplicationActuationResumed }?.timestamp

    return first() is ResourceActuationPaused ||
      // If the app was paused and has not yet resumed
      (appPaused != null && (appResumed == null || appResumed.isBefore(appPaused)))
  }

  private fun List<PersistentEvent>.isVetoed(): Boolean {
    return first() is ResourceActuationVetoed
  }

  private fun List<PersistentEvent>.isResumed(): Boolean {
    return first() is ResourceActuationResumed || first() is ApplicationActuationResumed
  }

  private fun List<PersistentEvent>.isCurrentlyUnresolvable(): Boolean {
    return first() is ResourceCheckUnresolvable
  }

  /**
   * Checks last 10 events for flapping between only ResourceActuationLaunched and ResourceDeltaDetected
   */
  private fun List<PersistentEvent>.isUnhappy(): Boolean {
    val recentSliceOfHistory = this.subList(0, Math.min(10, this.size))
    val filteredHistory = recentSliceOfHistory.filter { it is ResourceDeltaDetected || it is ResourceActuationLaunched }
    if (filteredHistory.size != recentSliceOfHistory.size) {
      // there are other events, we're not thrashing.
      return false
    }
    return true
  }

  /**
   * Returns a list of [ResourceSummary] for the specified application.
   *
   * This function assumes there's a single delivery config associated with the application.
   */
  fun getResourceSummariesFor(application: String): List<ResourceSummary> {
    return try {
      val deliveryConfig = repository.getDeliveryConfigForApplication(application)
      deliveryConfig.resources.map { resource ->
        resource.toResourceSummary(deliveryConfig)
      }
    } catch (e: NoSuchDeliveryConfigException) {
      emptyList()
    }
  }

  private fun Resource<*>.toResourceSummary(deliveryConfig: DeliveryConfig) =
    ResourceSummary(
      resource = this,
      status = getStatus(id),
      moniker = if (spec is Monikered) {
        (spec as Monikered).moniker
      } else {
        null
      },
      locations = if (spec is Locatable<*>) {
        SimpleLocations(
          account = (spec as Locatable<*>).locations.account,
          vpc = (spec as Locatable<*>).locations.vpc,
          regions = (spec as Locatable<*>).locations.regions.map { SimpleRegionSpec(it.name) }.toSet()
        )
      } else {
        null
      },
      artifact = deliveryConfig.let {
        findAssociatedArtifact(it)?.toResourceArtifactSummary()
      }
    )

  private fun DeliveryArtifact.toResourceArtifactSummary() = ResourceArtifactSummary(name, type, reference)
}
