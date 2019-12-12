/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.api;

import static com.netflix.spinnaker.keel.api.ApiAssertions.isValidServiceAccount;
import static com.netflix.spinnaker.keel.util.Assert.require;

import java.util.Map;
import lombok.Value;

/** External representation of a resource that would be submitted to the API. */
@Value
public class SubmittedResource<T extends ResourceSpec> {

  /** TODO(rz): Document. */
  private Map<String, Object> metadata;

  /** TODO(rz): Document. */
  private ApiVersion apiVersion;

  /** TODO(rz): Document. */
  private String kind;

  /** TODO(rz): Document. */
  private T spec;

  public SubmittedResource(
      Map<String, Object> metadata, ApiVersion apiVersion, String kind, T spec) {
    this.metadata = metadata;
    this.apiVersion = apiVersion;
    this.kind = kind;
    this.spec = spec;

    require(
        isValidServiceAccount(metadata.get("serviceAccount")),
        "serviceAccount must be a valid service account");
  }
}
