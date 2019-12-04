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
package com.netflix.spinnaker.keel.docker

import com.netflix.spinnaker.keel.docker.SortType.INCREASING
import com.netflix.spinnaker.keel.docker.SortType.SEMVER

enum class TagVersionStrategy(val regex: String, val sortType: SortType) {
  INCREASING_TAG("""^.*$""", INCREASING),
  SEMVER_TAG("""^.*$""", SEMVER),
  BRANCH_JOB_COMMIT_BY_JOB("""^master-h(\d+).*$""", INCREASING), // popular netflix strategy
  SEMVER_JOB_COMMIT_BY_JOB("""^v.*-h(\d+).*$""", INCREASING), // popular netflix strategy
  SEMVER_JOB_COMMIT_BY_SEMVER("""^v(.*)-h\d+.*$""", SEMVER); // popular netflix strategy
}

enum class SortType {
  INCREASING,
  SEMVER;
}
