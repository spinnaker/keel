/*
 * Copyright 2020 Netflix, Inc.
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
package com.netflix.spinnaker.keel.serialization.mixins

import com.fasterxml.jackson.databind.module.SimpleModule
import com.netflix.spinnaker.keel.api.ApiVersion

class MixinModule : SimpleModule("KeelMixinModule") {
  init {
    setMixInAnnotation(ApiVersion::class.java, ApiVersionMixin::class.java)

    // TODO(rz): Scan for abstract classes with the `MixinFor` annotation. ClassPathScanningCandidateComponentProvider
    //  does not have support (and will not support) for finding abstract classes. Until a real solution is added,
    //  mappings would need to be manually defined like above.
  }
}
