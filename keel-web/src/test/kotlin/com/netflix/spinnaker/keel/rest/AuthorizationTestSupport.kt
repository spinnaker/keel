package com.netflix.spinnaker.keel.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.rest.AuthorizationSupport.Action
import com.netflix.spinnaker.keel.rest.AuthorizationSupport.Source
import com.netflix.spinnaker.keel.rest.AuthorizationType.APPLICATION_AUTHZ
import com.netflix.spinnaker.keel.rest.AuthorizationType.CLOUD_ACCOUNT_AUTHZ
import com.netflix.spinnaker.keel.rest.AuthorizationType.SERVICE_ACCOUNT_AUTHZ
import dev.minutest.ContextBuilder
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

enum class AuthorizationType { APPLICATION_AUTHZ, SERVICE_ACCOUNT_AUTHZ, CLOUD_ACCOUNT_AUTHZ }

open class Permission(
  val authorizationType: AuthorizationType,
  open val action: Action,
  open val source: Source
)

data class ApplicationPermission(override val action: Action, override val source: Source) :
  Permission(APPLICATION_AUTHZ, action, source)

data class CloudAccountPermission(override val action: Action, override val source: Source) :
  Permission(CLOUD_ACCOUNT_AUTHZ, action, source)

data class ServiceAccountPermission(override val source: Source) :
  Permission(SERVICE_ACCOUNT_AUTHZ, Action.READ, source)

/**
 * Mocks the authorization for any and all API calls. Use judiciously!
 */
fun ContextBuilder<*>.mockAllApiAuthorization(authorizationSupport: AuthorizationSupport) {
  every {
    authorizationSupport.hasApplicationPermission(any<String>(), any(), any())
  } returns true
  every {
    authorizationSupport.hasApplicationPermission(any<Action>(), any(), any())
  } just Runs
  every {
    authorizationSupport.hasServiceAccountAccess(any<String>(), any())
  } returns true
  every {
    authorizationSupport.hasServiceAccountAccess(any<Source>(), any())
  } just Runs
  every {
    authorizationSupport.hasCloudAccountPermission(any<String>(), any(), any())
  } returns true
  every {
    authorizationSupport.hasCloudAccountPermission(any<Action>(), any(), any())
  } just Runs
}

/**
 * Mocks the authorization for the specified API requests and permissions.
 */
fun ContextBuilder<*>.mockApiAuthorization(
  authorizationSupport: AuthorizationSupport,
  apis: Map<ApiRequest, Set<Permission>>
) {
  // For each distinct permission in the map, mock the authorization call with the corresponding arguments
  // (READ/WRITE action, entity type) so that it passes authorization.
  apis.values.flatten().distinct().forEach { permission ->
    when (permission.authorizationType) {
      AuthorizationType.APPLICATION_AUTHZ -> run {
        every {
          authorizationSupport.hasApplicationPermission(permission.action.name, permission.source.name, any())
        } returns true
        every {
          authorizationSupport.hasApplicationPermission(permission.action, permission.source, any())
        } just Runs
      }
      AuthorizationType.SERVICE_ACCOUNT_AUTHZ -> run {
        every {
          authorizationSupport.hasServiceAccountAccess(permission.source.name, any())
        } returns true
        every {
          authorizationSupport.hasServiceAccountAccess(permission.source, any())
        } just Runs
      }
      AuthorizationType.CLOUD_ACCOUNT_AUTHZ -> run {
        every {
          authorizationSupport.hasCloudAccountPermission(permission.action.name, permission.source.name, any())
        } returns true
        every {
          authorizationSupport.hasCloudAccountPermission(permission.action, permission.source, any())
        } just Runs
      }
    }
  }
}

/**
 *  This function loops through the specified API requests and, for each request, adds a test that verifies
 *  authorization fails when the caller does not have the associated permissions, so that you don't have to
 *  manually write those tests in your REST controller test class.
 */
fun ContextBuilder<*>.testApiPermissions(
  mvc: MockMvc,
  jsonMapper: ObjectMapper = ObjectMapper(),
  authorizationSupport: AuthorizationSupport,
  apis: Map<ApiRequest, Set<Permission>>
) {
  context("caller is not authorized") {
    before {
      // For each distinct permission in the map, mock the authorization call with the corresponding arguments
      // (READ/WRITE action, entity type) so that it fails authorization.
      apis.values.flatten().distinct().forEach { permission ->
        when (permission.authorizationType) {
          AuthorizationType.APPLICATION_AUTHZ -> run {
            every {
              authorizationSupport.hasApplicationPermission(permission.action.name, permission.source.name, any())
            } returns false
            every {
              authorizationSupport.hasApplicationPermission(permission.action, permission.source, any())
            } throws AccessDeniedException("Nuh-uh!")
          }
          AuthorizationType.SERVICE_ACCOUNT_AUTHZ -> run {
            every {
              authorizationSupport.hasServiceAccountAccess(permission.source.name, any())
            } returns false
            every {
              authorizationSupport.hasServiceAccountAccess(permission.source, any())
            } throws AccessDeniedException("Nuh-uh!")
          }
          AuthorizationType.CLOUD_ACCOUNT_AUTHZ -> run {
            every {
              authorizationSupport.hasCloudAccountPermission(permission.action.name, permission.source.name, any())
            } returns false
            every {
              authorizationSupport.hasCloudAccountPermission(permission.action, permission.source, any())
            } throws AccessDeniedException("Nuh-uh!")
          }
        }
      }
    }

    // For each API in the map, define  a test that checks the response code is a 403 (FORBIDDEN).
    apis.keys.forEach { api ->
      val (method, uri) = api.request.split(Regex("""\s+"""))
      test("$api is forbidden") {
        val request = when (method) {
          "GET" -> get(uri)
          "POST" -> post(uri).addData(jsonMapper, api.data)
          "PUT" -> put(uri).addData(jsonMapper, api.data)
          "DELETE" -> delete(uri)
          else -> throw IllegalArgumentException("Unsupported method $method for test")
        }
          .accept(MediaType.APPLICATION_JSON_VALUE)
          .header("X-SPINNAKER-USER", "keel@keel.io")

        mvc.perform(request).andExpect(status().isForbidden)
      }
    }
  }
}

private fun MockHttpServletRequestBuilder.addData(jsonMapper: ObjectMapper, data: Any?) =
  if (data != null) {
    content(jsonMapper.writeValueAsString(data))
      .contentType(MediaType.APPLICATION_JSON)
  } else {
    this
  }

data class ApiRequest(val request: String, val data: Any? = null) {
  override fun toString() = request
}
