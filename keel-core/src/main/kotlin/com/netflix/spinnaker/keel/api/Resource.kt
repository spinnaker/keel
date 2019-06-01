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
package com.netflix.spinnaker.keel.api

/**
 * Internal representation of a resource.
 */
data class Resource<T : Any>(
  val apiVersion: ApiVersion,
  val kind: String, // TODO: create a type
  val metadata: ResourceMetadata,
  val spec: T
) {
  constructor(resource: SubmittedResource<T>, metadata: ResourceMetadata) :
    this(resource.apiVersion, resource.kind, metadata, resource.spec)
}

/**
 * External representation of a resource that would be submitted to the API
 * It doesn't need to contain metadata
 */
data class SubmittedResource<T : Any>(
  val apiVersion: ApiVersion,
  val kind: String,
  val spec: T
)
