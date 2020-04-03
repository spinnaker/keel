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
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.serviceAccount
import com.netflix.spinnaker.keel.exceptions.NoSuchEntityException
import com.netflix.spinnaker.keel.persistence.KeelRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * Support for authorization of REST API calls.
 *
 * Permission may be granted based on checking that the user (as identified by the X-SPINNAKER-USER request header,
 * or X509 client certificate) has access to the application with the right permission level (READ or WRITE) and,
 * optionally, for cases where the API will trigger actuation using the service account associated with the delivery
 * config, that the caller has access to that service account. Finally, if the API will attempt to read information
 * from the cloud provider directly (e.g. when retrieving resources to display on the UI), access to the cloud
 * account may also be checked for each applicable resource (implementors of [Locatable]).
 *
 * Use the following guidelines to determine what level of check is required for each API:
 *   - Operations that read data from keel’s database exclusively should require READ access to the application.
 *   - Operations that read data from keel’s database and from cloud infrastructure should require READ access to
 *     the application AND, for each resource returned, READ access to the corresponding cloud account.
 *   - Operations that trigger storing data in keel’s database, but do NOT trigger infrastructure changes, should
 *     require WRITE access to the application.
 *   - Operations that trigger actuation, whereby the service account will take action on behalf of the original caller,
 *     should check the caller has access to the service account, in addition to WRITE access to the application.
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

  /**
   * Verifies that the caller has the specified permission (action) to access the application associated with the
   * specified entity.
   */
  fun hasApplicationPermission(action: String, entity: String, identifier: String) =
    passes { hasApplicationPermission(Action.valueOf(action), Entity.valueOf(entity), identifier) }

  fun hasServiceAccountAccess(entity: String, identifier: String) =
    passes { hasServiceAccountAccess(Entity.valueOf(entity), identifier) }

  fun hasCloudAccountPermission(action: String, entity: String, identifier: String) =
    passes { hasCloudAccountPermission(Action.valueOf(action), Entity.valueOf(entity), identifier) }

  /**
   * Verifies that the caller has the specified permission (action) to access the application associated with the
   * specified entity.
   *
   * @throws AccessDeniedException if caller does not have the required permission.
   */
  fun hasApplicationPermission(action: Action, entity: Entity, identifier: String) {
    withAuthentication(entity, identifier) { auth ->
      val application = when (entity) {
        Entity.RESOURCE -> repository.getResource(identifier).application
        Entity.APPLICATION -> identifier
        Entity.DELIVERY_CONFIG -> repository.getDeliveryConfig(identifier).application
      }
      permissionEvaluator.hasPermission(auth, application, "APPLICATION", action.name)
        .also { allowed ->
          log.debug("[ACCESS {}] User {}: permission to {} application {}.", allowed.str, auth.principal, action.name,
            application)
        }
    }
  }

  /**
   * Verifies that the caller has access to the specified service account.
   *
   * @throws AccessDeniedException if caller does not have the required permission.
   */
  fun hasServiceAccountAccess(entity: Entity, identifier: String) {
    withAuthentication(entity, identifier) { auth ->
      val serviceAccount = when (entity) {
        Entity.RESOURCE -> repository.getResource(identifier).serviceAccount
        Entity.APPLICATION -> repository.getDeliveryConfigForApplication(identifier).serviceAccount
        Entity.DELIVERY_CONFIG -> repository.getDeliveryConfig(identifier).serviceAccount
      }
      permissionEvaluator.hasPermission(auth, serviceAccount, "SERVICE_ACCOUNT", "ignored")
        .also { allowed ->
          log.debug("[ACCESS {}] User {}: access to service account {}.", allowed.str, auth.principal, serviceAccount)
        }
    }
  }

  /**
   * Verifies that the caller has the specified permission to all applicable resources (i.e. resources whose specs
   * are [Locatable]) identified by the entity type and identifier, as follows:
   *   - If entity is RESOURCE, check the resource itself
   *   - If entity is DELIVERY_CONFIG, check all the resources in all the environments of the delivery config
   *   - If entity is APPLICATION, do the same as for DELIVERY_CONFIG
   *
   * @throws AccessDeniedException if caller does not have the required permission.
   */
  fun hasCloudAccountPermission(action: Action, entity: Entity, identifier: String) {
    withAuthentication(entity, identifier) { auth ->
      val locatableResources = when (entity) {
        Entity.RESOURCE -> listOf(repository.getResource(identifier))
        Entity.APPLICATION -> repository.getDeliveryConfigForApplication(identifier).resources
        Entity.DELIVERY_CONFIG -> repository.getDeliveryConfig(identifier).resources
      }.filter { it.spec is Locatable<*> }

      locatableResources.forEach {
        val account = (it.spec as Locatable<*>).locations.account
        permissionEvaluator.hasPermission(auth, account, "ACCOUNT", action.name)
          .also { allowed ->
            log.debug("[ACCESS {}] User {}: {} access to cloud account {}.", allowed.str, auth.principal, action.name, account)
            if (!allowed) {
              throw AccessDeniedException("User ${auth.principal} does not have access to cloud account $account")
            }
          }
      }
    }
  }

  private fun withAuthentication(entity: Entity, identifier: String, block: (Authentication) -> Unit): Boolean {
    return try {
      val auth = SecurityContextHolder.getContext().authentication
      block(auth)
      true
    } catch (e: NoSuchEntityException) {
      // If entity doesn't exist return true so a 404 is returned from the controller.
      log.debug("${entity.name} $identifier not found. Allowing request to return 404.")
      true
    }
  }

  private fun passes(authorizationCheck: () -> Unit) =
    try {
      authorizationCheck()
      true
    } catch (e: AccessDeniedException) {
      false
    }

  val Boolean.str: String
    get() = if (this) "ALLOWED" else "DENIED"
}
