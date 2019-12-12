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
package com.netflix.spinnaker.keel.plugin;

import com.netflix.spinnaker.keel.api.Resource;
import com.netflix.spinnaker.keel.api.Task;
import java.util.Map;

/**
 * TODO(rz): Document. TODO(rz): Also an issue of the original [OrcaTaskLauncher] exposing a
 * suspendable method. What are we supposed to do about these types? Perhaps expose a @Suspendable
 * annotation? Return a Future?
 */
public interface TaskLauncher {

  /** TODO(rz): Document. */
  Task submitJobToOrca(
      Resource<?> resource, String description, String correlationId, Map<String, Object> job);
}
