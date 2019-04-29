package com.netflix.spinnaker.keel.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.ApiVersion
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.ResourceMetadata
import com.netflix.spinnaker.keel.api.ResourceName
import com.netflix.spinnaker.keel.api.SubmittedResource
import com.netflix.spinnaker.keel.api.randomUID
import com.netflix.spinnaker.keel.events.TaskRef
import com.netflix.spinnaker.keel.exceptions.InvalidResourceStructureException
import org.slf4j.Logger

/**
 * @param S the spec type.
 * @param R the resolved model type.
 *
 * If those two are the same, use [ResourceHandler] instead.
 */
interface ResolvableResourceHandler<S : Any, R : Any> : KeelPlugin {

  val log: Logger

  val objectMapper: ObjectMapper

  val normalizers: List<ResourceNormalizer<*>>

  val apiVersion: ApiVersion

  /**
   * Maps the kind to the implementation type.
   */
  val supportedKind: Pair<ResourceKind, Class<S>>

  /**
   * Generate a unique name for a resource based on its spec.
   */
  fun generateName(spec: S): ResourceName

  /**
   * Validates the resource spec, and generates a metadata header, and applies any defaults /
   * normalization.
   *
   * Implementors should not generally need to override this method,
   *  custom normalization and validation should be done with a [ResourceNormalizer].
   *
   * @return a hydrated `Resource` with a name generated by convention, a `uid`, default values
   * applied, etc.
   */
  fun normalize(resource: SubmittedResource<Any>): Resource<S> {
    val spec: S
    try {
      spec = objectMapper.convertValue(resource.spec, supportedKind.second)
    } catch (e: IllegalArgumentException) {
      throw InvalidResourceStructureException(
        "Submitted resource with an incorrect structure, cannot convert to ${resource.kind} ${resource.spec}",
        resource.spec.toString(),
        e
      )
    }
    val metadata = ResourceMetadata(
      name = generateName(spec),
      uid = randomUID()
    )
    val hydratedResource = Resource(
      apiVersion = resource.apiVersion,
      kind = resource.kind,
      metadata = metadata,
      spec = spec
    )
    @Suppress("UNCHECKED_CAST")
    return normalize(hydratedResource as Resource<Any>)
  }

  /**
   * Validates the resource and applies any defaults / normalization.
   *
   * @return [resource] or a copy of [resource] that may have been changed, for example in order to
   * set default values.
   */
  @Suppress("UNCHECKED_CAST")
  fun normalize(resource: Resource<Any>): Resource<S> {
    var normalizedResource = resource.copy(
      spec = objectMapper.convertValue(resource.spec, supportedKind.second)
    ) as Resource<S>
    for (normalizer in normalizers) {
      if (normalizer.handles(resource.apiVersion, resource.kind)) {
        log.debug("Normalizing ${resource.metadata.name} with ${normalizer.javaClass}")
        normalizedResource = normalizer.normalize(normalizedResource) as Resource<S>
      }
    }
    return normalizedResource
  }

  /**
   * Resolve the resource spec into the desired state. This may involve looking up referenced
   * resources, etc.
   *
   * Implementations of this method should not actuate any changes.
   */
  fun desired(resource: Resource<S>): R

  /**
   * Return the current _actual_ representation of what [resource] looks like in the cloud.
   * The entire desired state is passed so that implementations can use whatever identifying
   * information they need to look up the resource.
   *
   * Implementations of this method should not actuate any changes.
   */
  fun current(resource: Resource<S>): R?

  /**
   * Create a resource so that it matches the desired state represented by [resource].
   *
   * By default this just delegates to [upsert].
   *
   * Implement this method and [update] if you need to handle create and update in different ways.
   * Otherwise just implement [upsert].
   *
   * @return a list of tasks launched to actuate the resource.
   */
  fun create(
    resource: Resource<S>,
    resourceDiff: ResourceDiff<R>
  ): List<TaskRef> =
    upsert(resource, resourceDiff)

  /**
   * Update a resource so that it matches the desired state represented by [resource].
   *
   * By default this just delegates to [upsert].
   *
   * Implement this method and [create] if you need to handle create and update in different ways.
   * Otherwise just implement [upsert].
   *
   * @return a list of tasks launched to actuate the resource.
   */
  fun update(
    resource: Resource<S>,
    resourceDiff: ResourceDiff<R>
  ): List<TaskRef> =
    upsert(resource, resourceDiff)

  /**
   * Create or update a resource so that it matches the desired state represented by [resource].
   *
   * You don't need to implement this method if you are implementing [create] and [update]
   * individually.
   *
   * @return a list of tasks launched to actuate the resource.
   */
  fun upsert(
    resource: Resource<S>,
    resourceDiff: ResourceDiff<R>
  ): List<TaskRef> {
    TODO("Not implemented")
  }

  /**
   * Delete a resource as the desired state is that it should no longer exist.
   */
  fun delete(resource: Resource<S>)
}
