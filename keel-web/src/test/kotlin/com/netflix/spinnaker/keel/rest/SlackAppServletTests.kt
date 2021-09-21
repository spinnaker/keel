package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.KeelApplication
import com.netflix.spinnaker.keel.notifications.slack.callbacks.CommitModalCallbackHandler
import com.netflix.spinnaker.keel.notifications.slack.callbacks.ManualJudgmentCallbackHandler
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpStatus.OK
import org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import strikt.api.expectThat
import strikt.assertions.isEqualTo
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset

@SpringBootTest(
  classes = [KeelApplication::class],
  webEnvironment = RANDOM_PORT,
  properties = [ "management.metrics.useNetflixConventions=false" ]
)
class SlackAppServletTests {
  @Autowired
  lateinit var restTemplate: TestRestTemplate

  @MockkBean
  lateinit var manualJudgementHandler: ManualJudgmentCallbackHandler

  @MockkBean
  lateinit var commitModalHandler: CommitModalCallbackHandler

  @BeforeEach
  fun setup() {
    every {
      manualJudgementHandler.respondToButton(any(), any())
    } just runs

    every {
      commitModalHandler.openModal(any(), any())
    } just runs
  }

  @Test
  fun `delegates handling of manual judgement callback`() {
    val request = postSlackCallback("/slack/manual-judgement-payload.json")
    val response: ResponseEntity<String> = restTemplate.postForEntity(request.url, request, String::class.java)

    expectThat(response.statusCode).isEqualTo(OK)

    verify {
      manualJudgementHandler.respondToButton(any(), any())
    }
  }

  @Test
  fun `delegates handling of commit modal callback`() {
    val request = postSlackCallback("/slack/show-commit-payload.json")
    val response: ResponseEntity<String> = restTemplate.postForEntity(request.url, request, String::class.java)

    expectThat(response.statusCode).isEqualTo(OK)

    verify {
      commitModalHandler.openModal(any(), any())
    }
  }

  private fun postSlackCallback(resourceName: String) = RequestEntity
    .method(POST, URI("/slack/notifications/callbacks"))
    .accept(APPLICATION_JSON)
    .contentType(APPLICATION_FORM_URLENCODED)
    .body("payload=" + javaClass.getResource(resourceName).readText()
      .let { URLEncoder.encode(it, Charset.forName("UTF-8")) }
    )
}