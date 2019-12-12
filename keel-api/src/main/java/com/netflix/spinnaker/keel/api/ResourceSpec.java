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

/** Implemented by all resource specs. */
public interface ResourceSpec {

  /**
   * The formal resource name. This is combined with the resource's API version prefix and kind to
   * form the fully-qualified [ResourceId].
   *
   * <p>This can be a property that is part of the spec, or derived from other properties. If the
   * latter remember to annotate the overridden property with
   * [com.fasterxml.jackson.annotation.JsonIgnore].
   */
  String getId();

  /**
   * The Spinnaker application this resource belongs to.
   *
   * <p>This can be a property that is part of the spec, or derived from other properties. If the
   * latter remember to annotate the overridden property with
   * [com.fasterxml.jackson.annotation.JsonIgnore].
   */
  String getApplication();
}
