package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.constraints.ConstraintState
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactVetoes
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactsSummary
import com.netflix.spinnaker.keel.core.api.PinnedEnvironment
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.UID
import com.netflix.spinnaker.keel.core.api.normalize
import com.netflix.spinnaker.keel.core.api.resources
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.events.ResourceCreated
import com.netflix.spinnaker.keel.events.ResourceEvent
import com.netflix.spinnaker.keel.events.ResourceUpdated
import com.netflix.spinnaker.keel.exceptions.DuplicateResourceIdException
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * A combined repository for delivery configs, artifacts, and resources.
 *
 * This paves the way for re-thinking how we interact with sql/storing of resources,
 * and updating our internal repository structure to allow storing delivery configs to more easily also
 * store artifacts and resources.
 *
 * This also gives us an easy place to emit telemetry and events around the usage of methods.
 *
 * TODO eb: refactor repository interaction so transactionality is easier.
 */
@Component
class CombinedRepository(
  val deliveryConfigRepository: DeliveryConfigRepository,
  val artifactRepository: ArtifactRepository,
  val resourceRepository: ResourceRepository,
  val clock: Clock,
  val publisher: ApplicationEventPublisher
) {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @Transactional(propagation = Propagation.REQUIRED)
  fun upsertDeliveryConfig(submittedDeliveryConfig: SubmittedDeliveryConfig): DeliveryConfig {
    val new = DeliveryConfig(
      name = submittedDeliveryConfig.name,
      application = submittedDeliveryConfig.application,
      serviceAccount = submittedDeliveryConfig.serviceAccount,
      artifacts = submittedDeliveryConfig.artifacts.transform(submittedDeliveryConfig.name),
      environments = submittedDeliveryConfig.environments.mapTo(mutableSetOf()) { env ->
        Environment(
          name = env.name,
          resources = env.resources.mapTo(mutableSetOf()) { resource ->
            resource
              .copy(metadata = mapOf("serviceAccount" to submittedDeliveryConfig.serviceAccount) + resource.metadata)
              .normalize()
          },
          constraints = env.constraints,
          notifications = env.notifications
        )
      }
    )

    validate(new)
    return upsertDeliveryConfig(new)
  }

  /**
   * Validates that resources have unique ids, throws an exception if invalid
   */
  private fun validate(config: DeliveryConfig) {
    val resources = config.environments.map { it.resources }.flatten().map { it.id }
    val distinct = resources.distinct()

    if (resources.size != distinct.size) {
      val duplicates = resources.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
      val envToResources: Map<String, MutableList<String>> = config.environments
        .map { env -> env.name to env.resources.map { it.id }.toMutableList() }.toMap()
      val envsAndDuplicateResources = envToResources
        .filterValues { rs: MutableList<String> ->
          // remove all the resources we don't care about from this mapping
          rs.removeIf { it !in duplicates }
          // if there are resources left that we care about, leave it in the map
          rs.isNotEmpty()
        }
      log.error("Validation failed for ${config.name}, duplicates found: $envsAndDuplicateResources")
      throw DuplicateResourceIdException(duplicates, envsAndDuplicateResources)
    }
  }

  private fun Set<DeliveryArtifact>.transform(deliveryConfigName: String) =
    map { artifact ->
      when (artifact) {
        is DockerArtifact -> artifact.copy(deliveryConfigName = deliveryConfigName)
        is DebianArtifact -> artifact.copy(deliveryConfigName = deliveryConfigName)
      }
    }.toSet()

  @Transactional(propagation = Propagation.REQUIRED)
  fun upsertDeliveryConfig(deliveryConfig: DeliveryConfig): DeliveryConfig {
    val old = try {
      getDeliveryConfig(deliveryConfig.name)
    } catch (e: NoSuchDeliveryConfigException) {
      null
    }

    deliveryConfig.resources.forEach { resource ->
      upsert(resource)
    }
    deliveryConfig.artifacts.forEach { artifact ->
      register(artifact)
    }
    storeDeliveryConfig(deliveryConfig)

    if (old != null) {
      removeResources(old, deliveryConfig)
    }
    return deliveryConfig
  }

  fun <T : ResourceSpec> upsert(resource: Resource<T>) {
    val existingResource = try {
      getResource(resource.id)
    } catch (e: NoSuchResourceException) {
      null
    }
    if (existingResource != null) {
      val diff = DefaultResourceDiff(resource.spec, existingResource.spec)
      if (diff.hasChanges()) {
        log.debug("Updating ${resource.id}")
        storeResource(resource)
        publisher.publishEvent(ResourceUpdated(resource, diff.toDeltaJson(), clock))
      }
    } else {
      log.debug("Creating $resource")
      storeResource(resource)
      publisher.publishEvent(ResourceCreated(resource, clock))
    }
  }

  /**
   * Deletes a delivery config and everything in it.
   */
  @Transactional(propagation = Propagation.REQUIRED)
  fun deleteDeliveryConfig(deliveryConfigName: String): DeliveryConfig {
    val deliveryConfig = deliveryConfigRepository.get(deliveryConfigName)

    deliveryConfig.environments.forEach { environment ->
      environment.resources.forEach { resource ->
        // resources must be removed from the environment then deleted
        deliveryConfigRepository.deleteResourceFromEnv(deliveryConfig.name, environment.name, resource.id)
        deleteResource(resource.id)
      }
      deliveryConfigRepository.deleteEnvironment(deliveryConfig.name, environment.name)
    }

    deliveryConfig.artifacts.forEach { artifact ->
      artifactRepository.delete(artifact)
    }

    deliveryConfigRepository.delete(deliveryConfig.name)

    return deliveryConfig
  }

  /**
   * Removes artifacts, environments, and resources that were present in the [old]
   * delivery config and are not present in the [new] delivery config
   */
  fun removeResources(old: DeliveryConfig, new: DeliveryConfig) {
    val newResources = new.resources.map { it.id }
    old.artifacts.forEach { artifact ->
      if (artifact !in new.artifacts) {
        log.debug("Updating config ${new.name}: removing artifact $artifact")
        artifactRepository.delete(artifact)
      }
    }

    old.environments
      .forEach { environment ->
        environment.resources.forEach { resource ->
          if (resource.id !in newResources) {
            log.debug("Updating config ${new.name}: removing resource ${resource.id} in environment ${environment.name}")
            deliveryConfigRepository.deleteResourceFromEnv(
              deliveryConfigName = old.name, environmentName = environment.name, resourceId = resource.id
            )
            deleteResource(resource.id)
          }
        }
        if (environment.name !in new.environments.map { it.name }) {
          log.debug("Updating config ${new.name}: removing environment ${environment.name}")
          deliveryConfigRepository.deleteEnvironment(new.name, environment.name)
        }
      }
  }

  // START Delivery config methods
  fun storeDeliveryConfig(deliveryConfig: DeliveryConfig) =
    deliveryConfigRepository.store(deliveryConfig)

  fun getDeliveryConfig(name: String): DeliveryConfig =
    deliveryConfigRepository.get(name)

  fun environmentFor(resourceId: String): Environment =
    deliveryConfigRepository.environmentFor(resourceId)

  fun deliveryConfigFor(resourceId: String): DeliveryConfig =
    deliveryConfigRepository.deliveryConfigFor(resourceId)

  fun getDeliveryConfigsByApplication(application: String): Collection<DeliveryConfig> =
    deliveryConfigRepository.getByApplication(application)

  fun deleteDeliveryConfigByApplication(application: String): Int =
    deliveryConfigRepository.deleteByApplication(application)

  fun deleteResourceFromEnv(deliveryConfigName: String, environmentName: String, resourceId: String) =
    deliveryConfigRepository.deleteResourceFromEnv(deliveryConfigName, environmentName, resourceId)

  fun deleteEnvironment(deliveryConfigName: String, environmentName: String) =
    deliveryConfigRepository.deleteEnvironment(deliveryConfigName, environmentName)

  fun storeConstraintState(state: ConstraintState) =
    deliveryConfigRepository.storeConstraintState(state)

  fun getConstraintState(deliveryConfigName: String, environmentName: String, artifactVersion: String, type: String): ConstraintState? =
    deliveryConfigRepository.getConstraintState(deliveryConfigName, environmentName, artifactVersion, type)

  fun getConstraintStateById(uid: UID): ConstraintState? =
    deliveryConfigRepository.getConstraintStateById(uid)

  fun deleteConstraintState(deliveryConfigName: String, environmentName: String, type: String) =
    deliveryConfigRepository.deleteConstraintState(deliveryConfigName, environmentName, type)

  fun constraintStateFor(application: String): List<ConstraintState> =
    deliveryConfigRepository.constraintStateFor(application)

  fun constraintStateFor(deliveryConfigName: String, environmentName: String, limit: Int): List<ConstraintState> =
    deliveryConfigRepository.constraintStateFor(deliveryConfigName, environmentName, limit)

  fun deliveryConfigsDueForCheck(minTimeSinceLastCheck: Duration, limit: Int): Collection<DeliveryConfig> =
    deliveryConfigRepository.itemsDueForCheck(minTimeSinceLastCheck, limit)
  // END DeliveryConfigRepository methods

  // START ResourceRepository methods
  fun allResources(callback: (ResourceHeader) -> Unit) =
    resourceRepository.allResources(callback)

  fun getResource(id: String): Resource<out ResourceSpec> =
    resourceRepository.get(id)

  fun hasManagedResources(application: String): Boolean =
    resourceRepository.hasManagedResources(application)

  fun getResourceIdsByApplication(application: String): List<String> =
    resourceRepository.getResourceIdsByApplication(application)

  fun getResourcesByApplication(application: String): List<Resource<*>> =
    resourceRepository.getResourcesByApplication(application)

  fun getSummaryByApplication(application: String): List<ResourceSummary> =
    resourceRepository.getSummaryByApplication(application)

  fun storeResource(resource: Resource<*>) =
    resourceRepository.store(resource)

  fun deleteResource(id: String) =
    resourceRepository.delete(id)

  fun deleteResourcesByApplication(application: String): Int =
    resourceRepository.deleteByApplication(application)

  fun resourceEventHistory(id: String, limit: Int): List<ResourceEvent> =
    resourceRepository.eventHistory(id, limit)

  fun resourceLastEvent(id: String): ResourceEvent? =
    resourceRepository.lastEvent(id)

  fun resourceAppendHistory(event: ResourceEvent) =
    resourceRepository.appendHistory(event)

  fun resourcesDueForCheck(minTimeSinceLastCheck: Duration, limit: Int): Collection<Resource<out ResourceSpec>> =
    resourceRepository.itemsDueForCheck(minTimeSinceLastCheck, limit)

  fun getResourceStatus(id: String): ResourceStatus =
    resourceRepository.getStatus(id)
  // END ResourceRepository methods

  // START ArtifactRepository methods
  fun register(artifact: DeliveryArtifact) {
    artifactRepository.register(artifact)
    publisher.publishEvent(ArtifactRegisteredEvent(artifact))
  }

  fun getArtifact(name: String, type: ArtifactType, deliveryConfigName: String): List<DeliveryArtifact> =
    artifactRepository.get(name, type, deliveryConfigName)

  fun getArtifact(name: String, type: ArtifactType, reference: String, deliveryConfigName: String): DeliveryArtifact =
    artifactRepository.get(name, type, reference, deliveryConfigName)

  fun getArtifact(deliveryConfigName: String, reference: String, type: ArtifactType): DeliveryArtifact =
    artifactRepository.get(deliveryConfigName, reference, type)

  fun isRegistered(name: String, type: ArtifactType): Boolean =
    artifactRepository.isRegistered(name, type)

  fun getAllArtifacts(type: ArtifactType?): List<DeliveryArtifact> =
    artifactRepository.getAll(type)

  fun storeArtifact(name: String, type: ArtifactType, version: String, status: ArtifactStatus?): Boolean =
    artifactRepository.store(name, type, version, status)

  fun storeArtifact(artifact: DeliveryArtifact, version: String, status: ArtifactStatus?): Boolean =
    artifactRepository.store(artifact, version, status)

  fun deleteArtifact(artifact: DeliveryArtifact) =
    artifactRepository.delete(artifact)

  fun artifactVersions(artifact: DeliveryArtifact): List<String> =
    artifactRepository.versions(artifact)

  fun artifactVersions(name: String, type: ArtifactType): List<String> =
    artifactRepository.versions(name, type)

  fun latestVersionApprovedIn(deliveryConfig: DeliveryConfig, artifact: DeliveryArtifact, targetEnvironment: String): String? =
    artifactRepository.latestVersionApprovedIn(deliveryConfig, artifact, targetEnvironment)

  fun approveVersionFor(deliveryConfig: DeliveryConfig, artifact: DeliveryArtifact, version: String, targetEnvironment: String): Boolean =
    artifactRepository.approveVersionFor(deliveryConfig, artifact, version, targetEnvironment)

  fun isApprovedFor(deliveryConfig: DeliveryConfig, artifact: DeliveryArtifact, version: String, targetEnvironment: String): Boolean =
    artifactRepository.isApprovedFor(deliveryConfig, artifact, version, targetEnvironment)

  fun markAsDeployingTo(deliveryConfig: DeliveryConfig, artifact: DeliveryArtifact, version: String, targetEnvironment: String) =
    artifactRepository.markAsDeployingTo(deliveryConfig, artifact, version, targetEnvironment)

  fun wasSuccessfullyDeployedTo(deliveryConfig: DeliveryConfig, artifact: DeliveryArtifact, version: String, targetEnvironment: String): Boolean =
    artifactRepository.wasSuccessfullyDeployedTo(deliveryConfig, artifact, version, targetEnvironment)

  fun markAsSuccessfullyDeployedTo(deliveryConfig: DeliveryConfig, artifact: DeliveryArtifact, version: String, targetEnvironment: String) =
    artifactRepository.markAsSuccessfullyDeployedTo(deliveryConfig, artifact, version, targetEnvironment)

  fun versionsByEnvironment(deliveryConfig: DeliveryConfig): List<EnvironmentArtifactsSummary> =
    artifactRepository.versionsByEnvironment(deliveryConfig)

  fun pinEnvironment(deliveryConfig: DeliveryConfig, environmentArtifactPin: EnvironmentArtifactPin) =
    artifactRepository.pinEnvironment(deliveryConfig, environmentArtifactPin)

  fun pinnedEnvironments(deliveryConfig: DeliveryConfig): List<PinnedEnvironment> =
    artifactRepository.pinnedEnvironments(deliveryConfig)

  fun deletePin(deliveryConfig: DeliveryConfig, targetEnvironment: String) =
    artifactRepository.deletePin(deliveryConfig, targetEnvironment)

  fun deletePin(deliveryConfig: DeliveryConfig, targetEnvironment: String, reference: String, type: ArtifactType) =
    artifactRepository.deletePin(deliveryConfig, targetEnvironment, reference, type)

  fun vetoedEnvironmentVersions(deliveryConfig: DeliveryConfig): List<EnvironmentArtifactVetoes> =
    artifactRepository.vetoedEnvironmentVersions(deliveryConfig)

  fun markAsVetoedIn(
    deliveryConfig: DeliveryConfig,
    artifact: DeliveryArtifact,
    version: String,
    targetEnvironment: String,
    force: Boolean = false
  ): Boolean =
    artifactRepository.markAsVetoedIn(deliveryConfig, artifact, version, targetEnvironment, force)

  fun deleteVeto(deliveryConfig: DeliveryConfig, artifact: DeliveryArtifact, version: String, targetEnvironment: String) =
    artifactRepository.deleteVeto(deliveryConfig, artifact, version, targetEnvironment)
  // END ArtifactRepository methods
}
