package com.netflix.spinnaker.keel.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.minutest.ContextBuilder
import io.mockk.every
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 *  This function loops through the APIs defined by the controller tests and dynamically adds a context/test for each,
 *  so you don't have to manually write those tests in your REST controller test classes. This avoids a lot of
 *  unnecessary repetition defining these API permission check tests.
 */
fun ContextBuilder<*>.testApiPermissions(
  mvc: MockMvc,
  jsonMapper: ObjectMapper,
  authorizationSupport: AuthorizationSupport,
  apis: Map<ApiRequest, AuthorizationSupport.Permission>
) {
  // For each API/permission pair in the map, define a context for that check, mock the authorization call with the
  // corresponding arguments (READ/WRITE action, entity type) so that it returns false, and define a test that checks
  // the reponse code is a 403 (FORBIDDEN).
  apis.forEach { (api, permission) ->
    val (method, uri) = api.request.split(Regex("""\s+"""))
    context("API permission check for $api") {
      before {
        every {
          authorizationSupport.userCan(permission.action.name, permission.entity.name, any())
        } returns false
      }

      test("$api is forbidden") {
        val request = when (method) {
          "GET" -> get(uri)
          "POST" -> post(uri).maybeAddData(jsonMapper, api.data)
          "PUT" -> put(uri).maybeAddData(jsonMapper, api.data)
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

private fun MockHttpServletRequestBuilder.maybeAddData(jsonMapper: ObjectMapper, data: Any?) =
  if (data != null) {
    content(jsonMapper.writeValueAsString(data))
      .contentType(MediaType.APPLICATION_JSON)
  } else {
    this
  }

data class ApiRequest(val request: String, val data: Any? = null) {
  override fun toString() = request
}
