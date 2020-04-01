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
 *  This function loops through the specified API requests and permissions and adds a test for each,
 *  so that you don't have to manually write those tests in your REST controller test classes.
 */
fun ContextBuilder<*>.testApiPermissions(
  mvc: MockMvc,
  jsonMapper: ObjectMapper,
  authorizationSupport: AuthorizationSupport,
  apis: Map<ApiRequest, AuthorizationSupport.Permission>
) {
  context("caller is not authorized") {
    before {
      // For each distinct permission in the map, mock the authorization call with the corresponding arguments
      // (READ/WRITE action, entity type) so that it returns false.
      apis.values.distinct().forEach { permission ->
        every {
          authorizationSupport.userCan(permission.action.name, permission.entity.name, any())
        } returns false
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
