package com.netflix.spinnaker.keel.clouddriver.model

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch
import com.natpryce.hamkrest.should.shouldNotMatch
import com.netflix.spinnaker.config.KeelConfiguration
import com.netflix.spinnaker.keel.clouddriver.ClouddriverService
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.junit.jupiter.api.Test
import retrofit.Endpoints
import retrofit.RestAdapter
import retrofit.client.Client
import retrofit.client.Header
import retrofit.client.Request
import retrofit.client.Response
import retrofit.converter.JacksonConverter
import retrofit.mime.TypedByteArray
import java.net.URL

abstract class BaseModelParsingTest<T> {

  private val mapper = KeelConfiguration().objectMapper()
  private val client = mock<Client>()
  private val cloudDriver = RestAdapter.Builder()
    .setEndpoint(Endpoints.newFixedEndpoint("https://spinnaker.💩"))
    .setClient(client)
    .setConverter(JacksonConverter(mapper))
    .build()
    .create(ClouddriverService::class.java)

  abstract val json: URL
  abstract val call: ClouddriverService.() -> T?
  abstract val expected: T

  @Test
  fun `can parse a CloudDriver response into the expected model`() {
    whenever(
      client.execute(any())
    ) doAnswer {
      it.getArgument<Request>(0).jsonResponse(body = json)
    }

    val response = cloudDriver.call()

    response shouldNotMatch equalTo<T?>(null)
    response shouldEqual expected
  }
}

fun Request.jsonResponse(
  status: Int = 200,
  reason: String = "OK",
  headers: List<Header> = emptyList(),
  body: URL
) =
  Response(
    url,
    status,
    reason,
    headers,
    TypedByteArray("application/json", body.readBytes())
  )

infix fun <T> T.shouldEqual(expected: T) {
  shouldMatch(equalTo(expected))
}
