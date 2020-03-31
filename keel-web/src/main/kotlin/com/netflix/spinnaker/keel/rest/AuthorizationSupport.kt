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
import com.netflix.spinnaker.keel.api.application
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
 * (e.g. delivery configs, resources, etc), and that service account having access to the corresponding application
 * in Spinnaker with the right permission level (READ or WRITE).
 */
@Component
class AuthorizationSupport(
  private val permissionEvaluator: FiatPermissionEvaluator,
  private val repository: KeelRepository
) {
  val log: Logger by lazy { LoggerFactory.getLogger(javaClass) }

  enum class Action {
    READ, WRITE;
    override fun toString() = name.toLowerCase()
  }

  enum class Entity {
    APPLICATION, DELIVERY_CONFIG, RESOURCE;
    override fun toString() = name.toLowerCase()
  }

  data class Permission(val action: Action, val entity: Entity)

  fun userCan(action: Action, entity: Entity, identifier: String): Boolean {
    return try {
      val auth = SecurityContextHolder.getContext().authentication
      val serviceAccount = when (entity) {
        Entity.RESOURCE -> repository.getResource(identifier).serviceAccount
        Entity.APPLICATION -> repository.getDeliveryConfigForApplication(identifier).serviceAccount
        Entity.DELIVERY_CONFIG -> repository.getDeliveryConfig(identifier).serviceAccount
      }
      val application = when (entity) {
        Entity.RESOURCE -> repository.getResource(identifier).application
        Entity.APPLICATION -> identifier
        Entity.DELIVERY_CONFIG -> repository.getDeliveryConfig(identifier).application
      }
      val hasServiceAccountAccess = permissionEvaluator.hasPermission(auth, serviceAccount, "SERVICE_ACCOUNT", "ignored")
        .also {
          log.debug("[ACCESS {}] User {}: access to service account {}.",
            if (it) "ALLOWED" else "DENIED", auth.principal, serviceAccount)
        }
      val hasApplicationAccess = permissionEvaluator.hasPermission(serviceAccount, application, "APPLICATION", action.name)
        .also {
          log.debug("[ACCESS {}] Service account {}: permission to {} {} in application {}.",
            if (it) "ALLOWED" else "DENIED", serviceAccount, action.name, "${entity.name} $identifier", application)
        }
      hasServiceAccountAccess && hasApplicationAccess
    } catch (e: NoSuchEntityException) {
      // If entity doesn't exist return true so a 404 is returned from the controller.
      log.debug("${entity.name} $identifier not found. Allowing request to return 404.")
      true
    }
  }

  fun userCan(action: String, entity: String, identifier: String) =
    userCan(Action.valueOf(action), Entity.valueOf(entity), identifier)
}
