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
package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.fiat.shared.FiatPermissionEvaluator
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.serviceAccount
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.NoSuchResourceException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

@Component
class AuthorizationSupport(
  private val permissionEvaluator: FiatPermissionEvaluator,
  private val repository: KeelRepository
) {
  val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

  fun userCanWriteResource(id: String): Boolean =
    try {
      val resource = repository.getResource(id)
      userCanWriteSpec(resource.serviceAccount, resource.id)
    } catch (e: NoSuchResourceException) {
      // If resource doesn't exist return true so a 404 is propagated from the controller.
      true
    }

  fun userCanReadResource(id: String) = userCanWriteResource(id)

  fun userCanWriteSpec(serviceAccount: String, specOrName: Any): Boolean {
    return userCanAccessServiceAccount(serviceAccount, "Resource: $specOrName")
  }

  fun userCanReadSpec(serviceAccount: String, specOrName: Any) = userCanWriteSpec(serviceAccount, specOrName)

  fun userCanWriteApplication(application: String): Boolean = try {
    val deliveryConfig = repository.getDeliveryConfigsByApplication(application).first()
    userCanWriteDeliveryConfig(deliveryConfig)
  } catch (e: NoSuchElementException) {
    // If there are no delivery configs for that application return true so this is handled correctly in the controller.
    true
  }

  fun userCanReadApplication(application: String) = userCanWriteApplication(application)

  fun userCanWriteDeliveryConfig(deliveryConfig: DeliveryConfig): Boolean {
    return userCanAccessServiceAccount(
      deliveryConfig.serviceAccount,
      "Delivery config for application ${deliveryConfig.application}: ${deliveryConfig.name}"
    )
  }

  fun userCanReadDeliveryConfig(deliveryConfig: DeliveryConfig) = userCanWriteDeliveryConfig(deliveryConfig)

  fun userCanWriteDeliveryConfig(deliveryConfigName: String): Boolean {
    val deliveryConfig = repository.getDeliveryConfig(deliveryConfigName)
    return userCanWriteDeliveryConfig(deliveryConfig)
  }

  fun userCanReadDeliveryConfig(deliveryConfigName: String) = userCanWriteDeliveryConfig(deliveryConfigName)

  fun userCanAccessServiceAccount(serviceAccount: String, specOrName: Any): Boolean {
    val auth = SecurityContextHolder.getContext().authentication
    val hasPermission = permissionEvaluator.hasPermission(auth, serviceAccount, "SERVICE_ACCOUNT", "ignored-svcAcct-auth")
    log.debug(
      "[AUTH] {} is trying to access service account {}. They{} have permission. {}",
      auth.principal,
      serviceAccount,
      if (hasPermission) "" else " DO NOT",
      specOrName
    )
    return hasPermission
  }
}
