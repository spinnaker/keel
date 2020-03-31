package com.netflix.spinnaker.keel.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.minutest.ContextBuilder
import io.mockk.every
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

fun ContextBuilder<*>.testApiPermissions(
  mvc: MockMvc,
  jsonMapper: ObjectMapper,
  authorizationSupport: AuthorizationSupport,
  apis: Map<ApiRequest, AuthorizationSupport.Permission>
) {
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
          "POST" -> post(uri)
            .content(jsonMapper.writeValueAsString(api.data))
            .contentType(MediaType.APPLICATION_JSON)
          "PUT" -> put(uri)
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

data class ApiRequest(val request: String, val data: Any? = null) {
  override fun toString() = request
}
