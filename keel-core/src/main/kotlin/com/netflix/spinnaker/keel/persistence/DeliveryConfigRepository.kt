package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.ArtifactType
import com.netflix.spinnaker.keel.api.ConstraintState
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.Locations
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.toSimpleLocations

interface DeliveryConfigRepository : PeriodicallyCheckedRepository<DeliveryConfig> {

  /**
   * Persists a [DeliveryConfig].
   */
  fun store(deliveryConfig: DeliveryConfig)

  /**
   * Retrieves a [DeliveryConfig] by its unique [name].
   *
   * @return The [DeliveryConfig]
   * @throws NoSuchDeliveryConfigException if [name] does not map to a persisted config
   */
  fun get(name: String): DeliveryConfig

  /**
   * Retrieve the [Environment] a resource belongs to, by the resource [id].
   */
  fun environmentFor(resourceId: ResourceId): Environment

  /**
   * Retrieve the [DeliveryConfig] a resource belongs to (the parent of its environment).
   */
  fun deliveryConfigFor(resourceId: ResourceId): DeliveryConfig

  /**
   * @return All [DeliveryConfig] instances associated with [application], or an empty collection if
   * there are none.
   */
  fun getByApplication(application: String): Collection<DeliveryConfig>

  /**
   * Delete the [DeliveryConfig] persisted for an application. This does not delete the underlying
   * resources.
   *
   * @return The number of deleted [DeliveryConfig]s.
   */
  fun deleteByApplication(application: String): Int

  /**
   * Updates state for a stateful [Environment] constraint.
   */
  fun storeConstraintState(state: ConstraintState)

  /**
   * Get the latest state of an [Environment] constraint for a specific artifact.
   *
   * @param deliveryConfigName the [DeliveryConfig] name
   * @param environmentName the [Environment] name
   * @param artifactVersion the version of the artifact we're checking constraint state for
   * @param type the type of constraint
   *
   * @return [ConstraintState] or `null` if the given constraint type has no state for
   * the given Artifact/Environment combination.
   */
  fun getConstraintState(
    deliveryConfigName: String,
    environmentName: String,
    artifactVersion: String,
    type: String
  ): ConstraintState?

  /**
   * Rolls up the most recent constraint states (maximum of one per (Environment, ConstraintType))
   * related to a application retrieved by its name.
   *
   * @param application the application name
   *
   * @return A list of the most recent [ConstraintState]'s by environment per type or an
   * empty list if none exist.
   */
  fun constraintStateFor(application: String): List<ConstraintState>

  /**
   * Retrieves recent [ConstraintState]'s for an [Environment].
   *
   * @param deliveryConfigName the [DeliveryConfig] name
   * @param environmentName the [Environment] name
   * @param limit the maximum number of [ConstraintState]'s to return, sorted by recency
   *
   * @return A list of up-to the most recent `limit` [ConstraintState]'s or an empty list if
   * none exist.
   */
  fun constraintStateFor(
    deliveryConfigName: String,
    environmentName: String,
    limit: Int = 10
  ): List<ConstraintState>
}

/**
 * Resolve [Locations] either from the spec or from [resource]'s [Environment]. One or the other
 * must be non-null or an exception is thrown.
 */
fun <S : Locatable<SimpleLocations>> DeliveryConfigRepository.resolveLocations(resource: Resource<S>): SimpleLocations {
  val specLocations = resource.spec.locations
  val envLocations = environmentFor(resource.id).locations?.toSimpleLocations()
  return checkNotNull(specLocations ?: envLocations) {
    "Locations must be specified either in the resource spec, or its environment"
  }
}

/**
 * Resolve [Locations] either from the spec or from [resource]'s [Environment]. One or the other
 * must be non-null or an exception is thrown.
 */
fun <S : Locatable<SubnetAwareLocations>> DeliveryConfigRepository.resolveLocations(resource: Resource<S>): SubnetAwareLocations {
  val specLocations = resource.spec.locations
  val envLocations = environmentFor(resource.id).locations
  return checkNotNull(specLocations ?: envLocations) {
    "Locations must be specified either in the resource spec, or its environment"
  }
}

sealed class NoSuchDeliveryConfigException(message: String) : RuntimeException(message)
class NoSuchDeliveryConfigName(name: String) : NoSuchDeliveryConfigException("No delivery config named $name exists in the repository")

class NoMatchingArtifactException(deliveryConfigName: String, type: ArtifactType, reference: String) :
  RuntimeException("No artifact with reference $reference and type $type found in delivery config $deliveryConfigName")

class OrphanedResourceException(id: ResourceId) : RuntimeException("Resource $id exists without being a part of a delivery config")
