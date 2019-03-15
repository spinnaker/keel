/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.*
import com.netflix.spinnaker.keel.exceptions.InvalidResourceStructureException
import de.danielbechler.diff.node.DiffNode
import org.slf4j.Logger

interface ResourceHandler<T : Any> : KeelPlugin {
  data class ResourceDiff<T : Any>(
    val source: T,
    val diff: DiffNode
  ) {
    constructor(source : T) : this(source, DiffNode.newRootNode())
  }


  val log: Logger

  val apiVersion: ApiVersion

  /**
   * Maps the kind to the implementation type.
   */
  val supportedKind: Pair<ResourceKind, Class<T>>

  val objectMapper: ObjectMapper

  val normalizers: List<ResourceNormalizer<*>>

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
  fun normalize(resource: SubmittedResource<*>): Resource<T> {
    val spec: T
    try {
      spec = objectMapper.convertValue(resource.spec, supportedKind.second)
    } catch (e: IllegalArgumentException){
      throw InvalidResourceStructureException(
        "Submitted resource with an incorrect structure, cannot convert to ${resource.kind} ${resource.spec}",
        resource.spec.toString(),
        e
      )
    }
    val metadata = ResourceMetadata(
      name = generateName(spec),
      resourceVersion = 0L,
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
   * Generate a unique name for a resource based on its spec.
   */
  fun generateName(spec: T): ResourceName

  /**
   * Validates the resource and applies any defaults / normalization.
   *
   * @return [resource] or a copy of [resource] that may have been changed, for example in order to
   * set default values.
   */
  @Suppress("UNCHECKED_CAST")
  fun normalize(resource: Resource<Any>): Resource<T> {
    var normalizedResource = resource.copy(
      spec = objectMapper.convertValue(resource.spec, supportedKind.second)
    ) as Resource<T>
    for (normalizer in normalizers) {
      if (normalizer.handles(resource.apiVersion, resource.kind)) {
        log.debug("Normalizing ${resource.metadata.name} with ${normalizer.javaClass}")
        normalizedResource = normalizer.normalize(normalizedResource) as Resource<T>
      }
    }
    return normalizedResource
  }

  /**
   * Return the current _actual_ representation of what [resource] looks like in the cloud.
   * The entire desired state is passed so that implementations can use whatever identifying
   * information they need to look up the resource. Implementations of this method should not
   * actuate any changes.
   */
  fun current(resource: Resource<T>): T?

  /**
   * Create a resource so that it matches the desired state represented by [resource].
   *
   * By default this just delegates to [upsert].
   *
   * Implement this method and [update] if you need to handle create and update in different ways.
   * Otherwise just implement [upsert].
   */
  fun create(resource: Resource<T>) {
    upsert(resource, null)
  }

  /**
   * Update a resource so that it matches the desired state represented by [resource].
   *
   * By default this just delegates to [upsert].
   *
   * Implement this method and [create] if you need to handle create and update in different ways.
   * Otherwise just implement [upsert].
   */
  fun update(resource: Resource<T>, resourceDiff: ResourceDiff<T> = ResourceDiff(resource.spec)) {
    upsert(resource, resourceDiff)
  }

  /**
   * Create or update a resource so that it matches the desired state represented by [resource].
   *
   * You don't need to implement this method if you are implementing [create] and [update]
   * individually.
   */
  fun upsert(resource: Resource<T>, resourceDiff: ResourceDiff<T>? = null) {
    TODO("Not implemented")
  }

  /**
   * Delete a resource as the desired state is that it should no longer exist.
   */
  fun delete(resource: Resource<T>)
}

/**
 * Searches a list of `ResourceHandler`s and returns the first that supports [apiVersion] and
 * [kind].
 *
 * @throws UnsupportedKind if no appropriate handlers are found in the list.
 */
internal fun List<ResourceHandler<*>>.supporting(
  apiVersion: ApiVersion,
  kind: String
): ResourceHandler<*> =
  find { it.apiVersion == apiVersion && it.supportedKind.first.singular == kind }
    ?: throw UnsupportedKind(apiVersion, kind)

internal class UnsupportedKind(apiVersion: ApiVersion, kind: String) :
  IllegalStateException("No resource handler supporting \"$kind\" in \"$apiVersion\" is available")
