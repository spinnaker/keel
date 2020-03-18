package com.netflix.spinnaker.keel.actuation

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceDiff
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.VersionedArtifactContainer
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.plugins.ResourceHandler
import com.netflix.spinnaker.keel.api.plugins.supporting
import com.netflix.spinnaker.keel.core.ResourceCurrentlyUnresolvable
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.events.ResourceActuationLaunched
import com.netflix.spinnaker.keel.events.ResourceActuationVetoed
import com.netflix.spinnaker.keel.events.ResourceCheckError
import com.netflix.spinnaker.keel.events.ResourceCheckUnresolvable
import com.netflix.spinnaker.keel.events.ResourceCreated
import com.netflix.spinnaker.keel.events.ResourceDeltaDetected
import com.netflix.spinnaker.keel.events.ResourceDeltaResolved
import com.netflix.spinnaker.keel.events.ResourceMissing
import com.netflix.spinnaker.keel.events.ResourceUpdated
import com.netflix.spinnaker.keel.events.ResourceValid
import com.netflix.spinnaker.keel.logging.TracingSupport.Companion.withTracingContext
import com.netflix.spinnaker.keel.pause.ActuationPauser
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.DiffFingerprintRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.plugin.CannotResolveCurrentState
import com.netflix.spinnaker.keel.plugin.CannotResolveDesiredState
import com.netflix.spinnaker.keel.telemetry.ResourceCheckSkipped
import com.netflix.spinnaker.keel.veto.VetoEnforcer
import com.netflix.spinnaker.keel.veto.VetoResponse
import java.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class ResourceActuator(
  private val resourceRepository: ResourceRepository,
  private val artifactRepository: ArtifactRepository,
  private val deliveryConfigRepository: DeliveryConfigRepository,
  private val diffFingerprintRepository: DiffFingerprintRepository,
  private val handlers: List<ResourceHandler<*, *>>,
  private val actuationPauser: ActuationPauser,
  private val vetoEnforcer: VetoEnforcer,
  private val publisher: ApplicationEventPublisher,
  private val clock: Clock
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  suspend fun <T : ResourceSpec> checkResource(resource: Resource<T>) {
    withTracingContext(resource) {
      val id = resource.id
      val plugin = handlers.supporting(resource.kind)

      if (actuationPauser.isPaused(resource)) {
        log.debug("Actuation for resource {} is paused, skipping checks", id)
        publisher.publishEvent(ResourceCheckSkipped(resource.kind, id, "ActuationPaused"))
        return@withTracingContext
      }

      if (plugin.actuationInProgress(resource)) {
        log.debug("Actuation for resource {} is already running, skipping checks", id)
        publisher.publishEvent(ResourceCheckSkipped(resource.kind, id, "ActuationInProgress"))
        return@withTracingContext
      }

      try {
        val (resolvedResource, desired, current) = plugin.resolve(resource)
        val diff = DefaultResourceDiff(desired, current)
        if (diff.hasChanges()) {
          diffFingerprintRepository.store(id, diff)
        }

        /**
         * [VersionedArtifactContainer] is a special [ResourceSpec] sub-type. When the resource under evaluation
         * has a spec of this type, it means it carries information about a delivery artifact and version (typically
         * compute resources).
         */
        val versionedArtifact = if (resolvedResource.spec is VersionedArtifactContainer) {
          resolvedResource.spec as VersionedArtifactContainer
        } else {
          null
        }

        val response = vetoEnforcer.canCheck(resource)
        if (!response.allowed) {
          /**
           * When a veto response sets [VetoResponse.vetoArtifact] and the resource has artifact information, blacklist
           * the desired artifact version from the environment containing [resource]. This ensures that the environment
           * will be fully restored to a prior good-state.
           */
          if (response.vetoArtifact && versionedArtifact != null) {
            try {
              val (version, artifact) = versionedArtifact.artifactVersion to versionedArtifact.deliveryArtifact
              if (version != null && artifact != null) {
                val deliveryConfig = deliveryConfigRepository.deliveryConfigFor(resource.id)
                val environment = deliveryConfig.environmentFor(resource)?.name
                  ?: error("Failed to find environment for ${resource.id} in deliveryConfig ${deliveryConfig.name} " +
                    "while attempting to veto artifact ${artifact.name} version $version")

                artifactRepository.markAsVetoedIn(
                  deliveryConfig = deliveryConfig,
                  artifact = artifact,
                  version = version,
                  targetEnvironment = environment)
                // TODO: emit event + metric
              }
            } catch (e: Exception) {
              log.warn("Failed to veto presumed bad artifact version for ${resource.id}", e)
              // TODO: emit metric
            }
          }
          log.debug("Skipping actuation for resource {} because it was vetoed: {}", id, response.message)
          publisher.publishEvent(ResourceCheckSkipped(resource.kind, id, response.vetoName))
          publishVetoedEvent(response, resource)
          return@withTracingContext
        }

        log.debug("Checking resource {}", id)

        when {
          current == null -> {
            log.warn("Resource {} is missing", id)
            publisher.publishEvent(ResourceMissing(resolvedResource, clock))

            plugin.create(resolvedResource, diff)
              .also { tasks ->
                publisher.publishEvent(ResourceCreated(resolvedResource, clock))
                publisher.publishEvent(ResourceActuationLaunched(resolvedResource, plugin.name, tasks, clock))
              }
          }
          diff.hasChanges() -> {
            log.warn("Resource {} is invalid", id)
            log.info("Resource {} delta: {}", id, diff.toDebug())
            publisher.publishEvent(ResourceDeltaDetected(resolvedResource, diff.toDeltaJson(), clock))

            plugin.update(resolvedResource, diff)
              .also { tasks ->
                publisher.publishEvent(ResourceUpdated(resolvedResource, diff.toDeltaJson(), clock))
                publisher.publishEvent(ResourceActuationLaunched(resolvedResource, plugin.name, tasks, clock))
              }
          }
          else -> {
            log.info("Resource {} is valid", id)
            // TODO: not sure this logic belongs here
            val lastEvent = resourceRepository.lastEvent(id)
            if (lastEvent is ResourceDeltaDetected || lastEvent is ResourceActuationLaunched) {
              publisher.publishEvent(ResourceDeltaResolved(resolvedResource, clock))
            } else {
              publisher.publishEvent(ResourceValid(resolvedResource, clock))
            }
          }
        }
      } catch (e: ResourceCurrentlyUnresolvable) {
        log.warn("Resource check for {} failed (hopefully temporarily) due to {}", id, e.message)
        publisher.publishEvent(ResourceCheckUnresolvable(resource, e, clock))
      } catch (e: Exception) {
        log.error("Resource check for $id failed", e)
        publisher.publishEvent(ResourceCheckError(resource, e, clock))
      }
    }
  }

  private suspend fun <T : Any> ResourceHandler<*, T>.resolve(resource: Resource<out ResourceSpec>):
    Triple<Resource<*>, T, T?> =
    supervisorScope {
      val desiredAsync = async {
        try {
          desired(resource)
        } catch (e: ResourceCurrentlyUnresolvable) {
          throw e
        } catch (e: Throwable) {
          throw CannotResolveDesiredState(resource.id, e)
        }
      }
      val currentAsync = async {
        try {
          current(resource)
        } catch (e: Throwable) {
          throw CannotResolveCurrentState(resource.id, e)
        }
      }
      val (desired, resolved) = desiredAsync.await()
      val current = currentAsync.await()
      Triple(resolved, desired, current)
    }

  /**
   * We want a specific status for specific types of vetos. This function publishes the
   * right event based on which veto said no.
   */
  private fun publishVetoedEvent(response: VetoResponse, resource: Resource<*>) =
    when {
      response.vetoName == "UnhappyVeto" -> {
        // don't publish an event, we want the status to stay as "unhappy" for clarity
      }
      else -> publisher.publishEvent(
        ResourceActuationVetoed(
          resource.kind,
          resource.id,
          resource.spec.application,
          response.message,
          clock.instant()))
    }

  private fun DeliveryConfig.environmentFor(resource: Resource<*>): Environment? =
    environments.firstOrNull { it.resources.contains(resource) }

  // These extensions get round the fact tht we don't know the spec type of the resource from
  // the repository. I don't want the `ResourceHandler` interface to be untyped though.
  @Suppress("UNCHECKED_CAST")
  private suspend fun <S : ResourceSpec, C : Any> ResourceHandler<S, C>.desired(
    resource: Resource<*>
  ): Pair<C, Resource<S>> =
    desired(resource as Resource<S>)

  @Suppress("UNCHECKED_CAST")
  private suspend fun <S : ResourceSpec, C : Any> ResourceHandler<S, C>.current(
    resource: Resource<*>
  ): C? =
    current(resource as Resource<S>)

  @Suppress("UNCHECKED_CAST")
  private suspend fun <S : ResourceSpec, C : Any> ResourceHandler<S, C>.create(
    resource: Resource<*>,
    resourceDiff: ResourceDiff<*>
  ): List<Task> =
    create(resource as Resource<S>, resourceDiff as ResourceDiff<C>)

  @Suppress("UNCHECKED_CAST")
  private suspend fun <S : ResourceSpec, C : Any> ResourceHandler<S, C>.update(
    resource: Resource<*>,
    resourceDiff: ResourceDiff<*>
  ): List<Task> =
    update(resource as Resource<S>, resourceDiff as ResourceDiff<C>)

  @Suppress("UNCHECKED_CAST")
  private suspend fun <S : ResourceSpec> ResourceHandler<S, *>.actuationInProgress(
    resource: Resource<*>
  ): Boolean =
    actuationInProgress(resource as Resource<S>)
  // end type coercing extensions
}
