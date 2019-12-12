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

import static java.lang.String.format;

import java.util.Arrays;
import lombok.Value;

/** TODO(rz): Document. */
@Value
public class ApiVersion {
  /** TODO(rz): Document. */
  private String group;

  /** TODO(rz): Document. */
  private String version;

  /** TODO(rz): Document. */
  private String prefix;

  public ApiVersion(String value) {
    String[] parts = value.split("/");
    String[] lastParts = Arrays.copyOfRange(parts, 1, parts.length - 1);
    this.group = parts[0];
    this.version = String.join("/", lastParts);
    this.prefix = group.split("\\.")[0];
  }

  public ApiVersion(String group, String version) {
    this.group = group;
    this.version = version;
    this.prefix = group.split("\\.")[0];
  }

  public ApiVersion subApi(String prefix) {
    return new ApiVersion(format("%s.%s", prefix, group), version);
  }

  @Override
  public String toString() {
    return format("%s/%s", group, version);
  }
}
