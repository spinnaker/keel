/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.keel.exceptions

import com.netflix.spinnaker.keel.api.Resource

sealed class InvalidResourceException(
  override val message: String?,
  override val cause: Throwable
) : RuntimeException(message, cause)

class FailedNormalizationException(
  errorMessage: String,
  resource: Resource<*>,
  cause: Throwable
) : InvalidResourceException(
  "Resource ${resource.metadata.name} failed normalization with error: $errorMessage. Resource: $resource", cause
)

class InvalidResourceStructureException(
  errorMessage: String,
  resource: String,
  cause: Throwable
) : InvalidResourceException(
  "Resource failed normalization with error: $errorMessage. Resource: $resource", cause
)
