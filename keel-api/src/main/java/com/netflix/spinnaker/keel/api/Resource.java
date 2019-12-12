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

import static com.netflix.spinnaker.keel.api.ApiAssertions.isValidApplication;
import static com.netflix.spinnaker.keel.api.ApiAssertions.isValidId;
import static com.netflix.spinnaker.keel.api.ApiAssertions.isValidServiceAccount;
import static com.netflix.spinnaker.keel.util.Assert.isNotEmpty;
import static com.netflix.spinnaker.keel.util.Assert.require;

import java.util.Map;
import lombok.Value;

/** TODO(rz): Document. */
@Value
public class Resource<T extends ResourceSpec> {
  /** TODO(rz): Document. */
  private ApiVersion apiVersion;

  /** TODO(rz): Document. */
  private String kind;

  /** TODO(rz): Document. */
  private Map<String, Object> metadata;

  /** TODO(rz): Document. */
  private T spec;

  public Resource(ApiVersion apiVersion, String kind, Map<String, Object> metadata, T spec) {
    this.apiVersion = apiVersion;
    this.kind = kind;
    this.metadata = metadata;
    this.spec = spec;
  }

  public Resource(SubmittedResource<T> resource, Map<String, Object> metadata) {
    this(resource.getApiVersion(), resource.getKind(), metadata, resource.getSpec());
  }

  private void validate() {
    require(isNotEmpty(kind), "resource kind must be defined");
    require(isValidId(metadata.get("id")), "resource id must be a valid id");
    require(
        isValidServiceAccount(metadata.get("serviceAccount")),
        "serviceAccount must be a valid service account");
    require(
        isValidApplication(metadata.get("application")), "application must be a valid application");
  }
}
