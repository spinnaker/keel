/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.platform

import com.google.common.base.MoreObjects
import com.netflix.appinfo.InstanceInfo
import com.netflix.spinnaker.kork.eureka.RemoteStatusChangedEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component

@Component
class DiscoveryStatusLogger(
  private val instanceInfo: InstanceInfo
) : ApplicationListener<RemoteStatusChangedEvent> {

  private val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

  override fun onApplicationEvent(event: RemoteStatusChangedEvent) {
    event.source.also {
      when {
        it.status == InstanceInfo.InstanceStatus.UP ->
          log.info("Instance is {} : {}", it.status, instanceInfo.info)
        it.previousStatus == InstanceInfo.InstanceStatus.UP ->
          log.warn("Instance just went {}", it.status)
      }
    }
  }

  private val InstanceInfo.info: String
    get() = MoreObjects
      .toStringHelper(this)
      .add("appName", appName)
      .add("asgName", asgName)
      .add("hostName", hostName)
      .add("instanceId", instanceId)
      .add("vip", vipAddress)
      .add("status", status)
      .toString()
}
