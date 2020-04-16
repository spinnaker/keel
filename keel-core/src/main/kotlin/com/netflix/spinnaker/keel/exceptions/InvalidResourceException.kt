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
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.kork.exceptions.UserException

sealed class InvalidResourceException(
  message: String?,
  cause: Throwable
) : UserException(message, cause)

class FailedNormalizationException(
  errorMessage: String,
  resource: Resource<*>,
  cause: Throwable
) : InvalidResourceException(
  "Resource ${resource.id} failed normalization with error: $errorMessage. Resource: $resource", cause
)
