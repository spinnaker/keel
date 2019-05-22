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
package com.netflix.spinnaker.keel.clouddriver

import com.netflix.spinnaker.keel.clouddriver.model.Image
import com.netflix.spinnaker.keel.clouddriver.model.NamedImage
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ImageService(
  val cloudDriverService: CloudDriverService
) {
  val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

  /**
   * version like keel-0.173.0-h79.ff1948a
   */
  suspend fun getLatestImage(artifactName: String, version: String, account: String): Image? {
    return cloudDriverService.namedImages(version, account)
      .await()
      .lastOrNull()
      ?.let { namedImage ->
        val tags = namedImage
          .tagsByImageId
          .values
          .first { it?.containsKey("base_ami_version") ?: false && it?.containsKey("appversion") ?: false }
        if (tags == null) {
          log.debug("Image not found for {} version {}", artifactName, version)
          return null
        } else {
          val image = Image(
            tags.getValue("base_ami_version")!!,
            tags.getValue("appversion")!!.substringBefore('/'),
            namedImage.amis.keys
          )
          log.debug("Latest image for {} version {} is {}", artifactName, version, image)
          return image
        }
      }
  }

  /**
   * Get the latest named image for a package
   */
  suspend fun getLatestNamedImage(packageName: String, account: String): NamedImage? {
    return cloudDriverService.namedImages(packageName, account)
      .await()
      .lastOrNull()
  }
}
