package com.netflix.spinnaker.keel.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.KeelApplication
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.diff.AdHocDiffer
import com.netflix.spinnaker.keel.persistence.memory.InMemoryResourceRepository
import com.netflix.spinnaker.keel.rest.AuthorizationSupport.Action.READ
import com.netflix.spinnaker.keel.rest.AuthorizationSupport.Action.WRITE
import com.netflix.spinnaker.keel.rest.AuthorizationSupport.Entity.APPLICATION
import com.netflix.spinnaker.keel.rest.AuthorizationSupport.Entity.RESOURCE
import com.netflix.spinnaker.keel.rest.AuthorizationType.APPLICATION_AUTHZ
import com.netflix.spinnaker.keel.rest.AuthorizationType.CLOUD_ACCOUNT_AUTHZ
import com.netflix.spinnaker.keel.rest.AuthorizationType.SERVICE_ACCOUNT_AUTHZ
import com.netflix.spinnaker.keel.spring.test.MockEurekaConfiguration
import com.netflix.spinnaker.keel.test.resource
import com.netflix.spinnaker.keel.test.submittedResource
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML
import com.ninjasquad.springmockk.MockkBean
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import strikt.api.Assertion
import strikt.api.DescribeableBuilder
import strikt.api.expectThat
import strikt.assertions.isNotNull

@ExtendWith(SpringExtension::class)
@SpringBootTest(
  classes = [KeelApplication::class, MockEurekaConfiguration::class, DummyResourceConfiguration::class],
  webEnvironment = MOCK
)
@AutoConfigureMockMvc
internal class ResourceControllerTests : JUnit5Minutests {
  @Autowired
  lateinit var mvc: MockMvc

  @Autowired
  lateinit var resourceRepository: InMemoryResourceRepository

  @MockkBean
  lateinit var authorizationSupport: AuthorizationSupport

  @Autowired
  @Qualifier("jsonMapper")
  lateinit var jsonMapper: ObjectMapper

  @MockkBean
  lateinit var adHocDiffer: AdHocDiffer

  var resource = resource()

  fun tests() = rootContext {
    before {
      every { authorizationSupport.hasApplicationPermission(READ.name, RESOURCE.name, any()) } returns true
      every { authorizationSupport.hasCloudAccountPermission(READ.name, RESOURCE.name, any()) } returns true
    }

    after {
      resourceRepository.dropAll()
    }

    test("an invalid request body results in an HTTP 400") {
      val request = post("/resources/diff")
        .accept(APPLICATION_YAML)
        .contentType(APPLICATION_YAML)
        .header("X-SPINNAKER-USER", "fzlem@netflix.com")
        .content(
          """---
          |metadata:
          |  name: i-forgot-my-kind
          |spec:
          |  data: o hai"""
            .trimMargin()
        )
      mvc
        .perform(request)
        .andExpect(status().isBadRequest)
    }

    test("can get a resource as YAML") {
      resourceRepository.store(resource)
      val request = get("/resources/${resource.id}")
        .accept(APPLICATION_YAML)
      val result = mvc
        .perform(request)
        .andExpect(status().isOk)
        .andReturn()
      expectThat(result.response)
        .contentType
        .isNotNull()
        .isCompatibleWith(APPLICATION_YAML)
    }

    test("unknown resource name results in a 404") {
      val request = get("/resources/i-do-not-exist")
        .accept(APPLICATION_YAML)
      mvc
        .perform(request)
        .andExpect(status().isNotFound)
    }

    testApiPermissions(mvc, jsonMapper, authorizationSupport, mapOf(
      ApiRequest("GET /resources/${resource.id}") to setOf(
        Permission(APPLICATION_AUTHZ, READ, RESOURCE),
        Permission(CLOUD_ACCOUNT_AUTHZ, READ, RESOURCE)
      ),
      ApiRequest("GET /resources/${resource.id}/status") to setOf(
        Permission(APPLICATION_AUTHZ, READ, RESOURCE),
        Permission(CLOUD_ACCOUNT_AUTHZ, READ, RESOURCE)
      ),
      ApiRequest("POST /resources/${resource.id}/pause") to setOf(
        Permission(APPLICATION_AUTHZ, WRITE, RESOURCE)
      ),
      ApiRequest("DELETE /resources/${resource.id}/pause") to setOf(
        Permission(APPLICATION_AUTHZ, WRITE, RESOURCE),
        Permission(SERVICE_ACCOUNT_AUTHZ, READ, RESOURCE)
      ),
      ApiRequest("POST /resources/diff", submittedResource()) to setOf(
        Permission(APPLICATION_AUTHZ, READ, APPLICATION)
      )
    ))
  }
}

private val Assertion.Builder<MockHttpServletResponse>.contentType: DescribeableBuilder<MediaType?>
  get() = get { contentType?.let(MediaType::parseMediaType) }

@Suppress("UNCHECKED_CAST")
private fun <T : MediaType?> Assertion.Builder<T>.isCompatibleWith(expected: MediaType): Assertion.Builder<MediaType> =
  assertThat("is compatible with $expected") {
    it?.isCompatibleWith(expected) ?: false
  } as Assertion.Builder<MediaType>
