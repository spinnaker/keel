package com.netflix.spinnaker.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.lemur.LemurService
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders.AUTHORIZATION
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory

@Configuration
@ConditionalOnProperty("lemur.base-url")
class LemurConfiguration {
  @Bean
  fun lemurEndpoint(
    @Value("\${lemur.base-url}") lemurBaseUrl: String
  ): HttpUrl =
    lemurBaseUrl.toHttpUrlOrNull()
      ?: throw BeanCreationException("Invalid URL: $lemurBaseUrl")

  @Bean
  fun lemurService(
    objectMapper: ObjectMapper,
    lemurEndpoint: HttpUrl,
    @Value("\${lemur.token}") token: String
  ): LemurService {
    val client = OkHttpClient
      .Builder()
      .addInterceptor { chain ->
        chain.proceed(
          chain
            .request()
            .newBuilder()
            .header(AUTHORIZATION, "Bearer $token")
            .build()
        )
      }
      .build()
    return Retrofit.Builder()
      .addConverterFactory(JacksonConverterFactory.create(objectMapper))
      .baseUrl(lemurEndpoint)
      .client(client)
      .build()
      .create(LemurService::class.java)
  }
}
