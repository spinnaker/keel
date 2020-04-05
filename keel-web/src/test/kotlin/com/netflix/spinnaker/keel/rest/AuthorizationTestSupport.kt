package com.netflix.spinnaker.keel.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.rest.AuthorizationSupport.Action
import com.netflix.spinnaker.keel.rest.AuthorizationSupport.SourceEntity
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder

/**
 * Mocks authorization to pass for any and all API calls.
 */
fun AuthorizationSupport.allowAll() {
  every {
    hasApplicationPermission(any<String>(), any(), any())
  } returns true
  every {
    hasApplicationPermission(any<Action>(), any(), any())
  } just Runs
  every {
    hasServiceAccountAccess(any<String>(), any())
  } returns true
  every {
    hasServiceAccountAccess(any<SourceEntity>(), any())
  } just Runs
  every {
    hasCloudAccountPermission(any<String>(), any(), any())
  } returns true
  every {
    hasCloudAccountPermission(any<Action>(), any(), any())
  } just Runs
}

/**
 * Mocks authorization to fail for [AuthorizationSupport.hasApplicationPermission].
 */
fun AuthorizationSupport.denyApplicationAccess(action: Action, source: SourceEntity) {
  every {
    hasApplicationPermission(action.name, source.name, any())
  } returns false
  every {
    hasApplicationPermission(action, source, any())
  } throws AccessDeniedException("Nuh-uh!")
}

/**
 * Mocks authorization to fail for [AuthorizationSupport.hasCloudAccountPermission].
 */
fun AuthorizationSupport.denyCloudAccountAccess(action: Action, source: SourceEntity) {
  every {
    hasCloudAccountPermission(action.name, source.name, any())
  } returns false
  every {
    hasCloudAccountPermission(action, source, any())
  } throws AccessDeniedException("Nuh-uh!")
}

/**
 * Mocks authorization to fail for [AuthorizationSupport.hasServiceAccountAccess].
 */
fun AuthorizationSupport.denyServiceAccountAccess(source: SourceEntity) {
  every {
    hasServiceAccountAccess(source.name, any())
  } returns false
  every {
    hasServiceAccountAccess(source, any())
  } throws AccessDeniedException("Nuh-uh!")
}

fun MockHttpServletRequestBuilder.addData(jsonMapper: ObjectMapper, data: Any?): MockHttpServletRequestBuilder =
  if (data != null) {
    content(jsonMapper.writeValueAsString(data))
      .contentType(MediaType.APPLICATION_JSON)
  } else {
    this
  }
