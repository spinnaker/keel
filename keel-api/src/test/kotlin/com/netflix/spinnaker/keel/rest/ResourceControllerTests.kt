package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.KeelApplication
import com.netflix.spinnaker.keel.annealing.ResourcePersister
import com.netflix.spinnaker.keel.api.ApiVersion
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceMetadata
import com.netflix.spinnaker.keel.api.ResourceName
import com.netflix.spinnaker.keel.api.randomUID
import com.netflix.spinnaker.keel.events.ResourceDeleted
import com.netflix.spinnaker.keel.persistence.ResourceState.Diff
import com.netflix.spinnaker.keel.persistence.ResourceState.Ok
import com.netflix.spinnaker.keel.persistence.ResourceStateHistoryEntry
import com.netflix.spinnaker.keel.persistence.memory.InMemoryResourceRepository
import com.netflix.spinnaker.keel.redis.spring.MockEurekaConfiguration
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML
import com.netflix.spinnaker.time.MutableClock
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.verifyBlocking
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBodyList
import java.time.Duration

@ExtendWith(SpringExtension::class)
@SpringBootTest(
  classes = [KeelApplication::class, MockEurekaConfiguration::class, MockTimeConfiguration::class],
  properties = [
    "clouddriver.baseUrl=https://localhost:8081",
    "orca.baseUrl=https://localhost:8082",
    "front50.baseUrl=https://localhost:8083"
  ],
  webEnvironment = RANDOM_PORT
)
@AutoConfigureWebTestClient
internal class ResourceControllerTest {
  @Autowired
  lateinit var client: WebTestClient

  @Autowired
  lateinit var resourceRepository: InMemoryResourceRepository

  @MockBean
  lateinit var resourcePersister: ResourcePersister

  @Autowired
  lateinit var clock: MutableClock

  var resource = Resource(
    apiVersion = ApiVersion("ec2.spinnaker.netflix.com/v1"),
    kind = "securityGroup",
    metadata = ResourceMetadata(
      name = ResourceName("ec2:securityGroup:test:us-west-2:keel"),
      uid = randomUID()
    ),
    spec = "mockingThis"
  )

  @AfterEach
  fun clearRepository() {
    resourceRepository.dropAll()
  }

  @Test
  fun `can create a resource as YAML`() {
    resourcePersister.stub {
      onBlocking { handle(any()) } doReturn resource
    }

    client
      .post()
      .uri("/resources")
      .accept(APPLICATION_YAML)
      .contentType(APPLICATION_YAML)
      .syncBody(
        """---
          |apiVersion: ec2.spinnaker.netflix.com/v1
          |kind: securityGroup
          |spec:
          |  account: test
          |  region: us-west-2
          |  name: keel"""
          .trimMargin()
      )
      .exchange()
      .expectStatus()
      .isCreated
  }

  @Test
  fun `can create a resource as JSON`() {
    resourcePersister.stub {
      onBlocking { handle(any()) } doReturn resource
    }

    client
      .post()
      .uri("/resources")
      .accept(APPLICATION_JSON)
      .contentType(APPLICATION_JSON)
      .syncBody(
        """{
          |  "apiVersion": "ec2.spinnaker.netflix.com/v1",
          |  "kind": "securityGroup",
          |  "spec": {
          |    "account": "test",
          |    "region": "us-west-2",
          |    "name": "keel"
          |  }
          |}"""
          .trimMargin()
      )
      .exchange()
      .expectStatus()
      .isCreated
  }

  @Test
  fun `an invalid request body results in an HTTP 400`() {
    client
      .post()
      .uri("/resources")
      .accept(APPLICATION_YAML)
      .contentType(APPLICATION_YAML)
      .syncBody(
        """---
          |apiVersion: ec2.spinnaker.netflix.com/v1
          |kind: securityGroup
          |metadata:
          |  name: i-should-not-be-naming-my-resources-that-is-keels-job
          |spec:
          |  account: test
          |  region: us-west-2
          |  name: keel"""
          .trimMargin()
      )
      .exchange()
      .expectStatus()
      .isBadRequest
  }

  @Test
  fun `can get a resource as YAML`() {
    runBlocking {
      resourceRepository.store(resource)
    }

    client
      .get()
      .uri("/resources/${resource.metadata.name}")
      .accept(APPLICATION_YAML)
      .exchange()
      .expectStatus()
      .isOk
      .expectHeader()
      .contentTypeCompatibleWith(APPLICATION_YAML)
  }

  @Test
  fun `can delete a resource`() {
    resourcePersister.stub {
      onBlocking { handle(any()) } doReturn resource
    }

    runBlocking {
      resourceRepository.store(resource)
    }

    client
      .delete()
      .uri("/resources/${resource.metadata.name}")
      .accept(APPLICATION_JSON)
      .exchange()
      .expectStatus()
      .isOk

    verifyBlocking(resourcePersister) {
      handle(ResourceDeleted(resource.metadata.name))
    }

    //clean up after the test
    runBlocking {
      resourceRepository.delete(resource.metadata.name)
    }
  }

  @Test
  fun `unknown resource name results in a 404`() {
    client
      .get()
      .uri("/resources/i-do-not-exist")
      .accept(APPLICATION_YAML)
      .exchange()
      .expectStatus()
      .isNotFound
  }

  @Test
  fun `can get state history for a resource`() {
    with(resourceRepository) {
      runBlocking {
        store(resource)
        sequenceOf(Ok, Diff, Ok).forEach {
          clock.incrementBy(Duration.ofMinutes(10))
          updateState(resource.metadata.uid, it)
        }
      }
    }

    client
      .get()
      .uri("/resources/${resource.metadata.name}/history")
      .accept(APPLICATION_JSON)
      .exchange()
      .expectStatus()
      .isOk
      .expectBodyList<ResourceStateHistoryEntry>()
      .hasSize(4)
  }
}

@Configuration
class MockTimeConfiguration {
  @Bean
  @Primary
  fun clock() = MutableClock()
}
