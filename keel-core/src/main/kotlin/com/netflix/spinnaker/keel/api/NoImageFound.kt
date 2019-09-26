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
package com.netflix.spinnaker.keel.api

class NoImageFound(artifactName: String) :
  RuntimeException("No image found for artifact $artifactName")

class NoImageFoundForRegion(artifactName: String, region: String) :
  RuntimeException("No image found for artifact $artifactName in region $region")

class NoImageFoundForRegions(artifactName: String, regions: Collection<String>) :
  RuntimeException("No image found for artifact $artifactName in regions ${regions.joinToString()}")

class NoImageSatisfiesConstraints(artifactName: String, environment: String) :
  RuntimeException("No image found for artifact $artifactName in $environment")
