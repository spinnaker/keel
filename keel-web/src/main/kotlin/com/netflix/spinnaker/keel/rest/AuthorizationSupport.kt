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
import com.netflix.spinnaker.keel.api.serviceAccount
import com.netflix.spinnaker.keel.exceptions.NoSuchEntityException
import com.netflix.spinnaker.keel.persistence.KeelRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * Support for authorization of REST API calls.
 *
 * Permission is granted based on the user (as identified by the X-SPINNAKER-USER request header, or X509 client
 * certificate, or metatron identity) having access to the "service account" associated with keel's entities
 * (e.g. delivery configs, resources, etc).
 *
 * Although the authorization function names seem to imply a difference between read and write permissions to those
 * entities, currently they're ignored -- we just check that a user has membership in the service account, but we keep
 * the distinction in place for future implementation.
 */
@Component
class AuthorizationSupport(
  private val permissionEvaluator: FiatPermissionEvaluator,
  private val repository: KeelRepository
) {
  val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

  enum class Action {
    READ, WRITE
  }

  enum class Entity {
    APPLICATION, DELIVERY_CONFIG, RESOURCE
  }

  fun userCan(action: Action, entity: Entity, identifier: String) =
    try {
      val serviceAccount = when (entity) {
        Entity.RESOURCE -> repository.getResource(identifier).serviceAccount
        Entity.APPLICATION -> repository.getDeliveryConfigForApplication(identifier).serviceAccount
        Entity.DELIVERY_CONFIG -> repository.getDeliveryConfig(identifier).serviceAccount
      }
      userCanAccessServiceAccount(serviceAccount, "${entity.name} $identifier")
    } catch (e: NoSuchEntityException) {
      // If entity doesn't exist return true so a 404 is returned from the controller.
      log.debug("${entity.name} $identifier not found. Allowing request to return 404.")
      true
    }

  fun userCanReadResource(id: String) =
    userCan(Action.READ, Entity.RESOURCE, id)

  fun userCanWriteResource(id: String) =
    userCan(Action.WRITE, Entity.RESOURCE, id)

  fun userCanReadApplication(application: String) =
    userCan(Action.READ, Entity.APPLICATION, application)

  fun userCanWriteApplication(application: String) =
    userCan(Action.WRITE, Entity.APPLICATION, application)

  fun userCanReadDeliveryConfig(deliveryConfigName: String) =
    userCan(Action.READ, Entity.DELIVERY_CONFIG, deliveryConfigName)

  fun userCanWriteDeliveryConfig(deliveryConfigName: String) =
    userCan(Action.WRITE, Entity.DELIVERY_CONFIG, deliveryConfigName)

  fun userCanAccessServiceAccount(serviceAccount: String, entityDescription: String): Boolean {
    val auth = SecurityContextHolder.getContext().authentication
    val hasPermission = permissionEvaluator.hasPermission(auth, serviceAccount, "SERVICE_ACCOUNT", "ignored-svcAcct-auth")
    log.debug(
      "[AUTH] {} is trying to access service account {}. They{} have permission. {}",
      auth.principal,
      serviceAccount,
      if (hasPermission) "" else " DO NOT",
      entityDescription
    )
    return hasPermission
  }
}
