/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.retrofit

import com.netflix.spinnaker.config.OkHttp3ClientConfiguration
import com.netflix.spinnaker.okhttp.OkHttpClientConfigurationProperties
import java.util.concurrent.TimeUnit.MILLISECONDS
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Scope

@Configuration
@Import(OkHttp3ClientConfiguration::class)
@EnableConfigurationProperties(OkHttpClientConfigurationProperties::class, KeelRetrofitProperties::class)
class KeelRetrofitConfiguration {

  private val log = LoggerFactory.getLogger(javaClass)

  @Bean(name = ["retrofitClient", "okClient"])
  @Scope(SCOPE_PROTOTYPE)
  fun retrofitClient(
    okHttpClientConfig: OkHttp3ClientConfiguration,
    okHttpClientProperties: OkHttpClientConfigurationProperties,
    retrofitProperties: KeelRetrofitProperties,
    interceptors: Set<Interceptor>?
  ): OkHttpClient = okHttpClientConfig
    .create()
    .apply {
      networkInterceptors()
        .add(Interceptor { chain ->
          chain.proceed(
            chain.request().newBuilder()
              .header("User-Agent", retrofitProperties.userAgent)
              .build()
          )
        })
      interceptors?.forEach {
        log.info("Adding OkHttp Interceptor: ${it.javaClass.simpleName}")
        addNetworkInterceptor(it)
      }

      connectionPool(ConnectionPool(
        okHttpClientProperties.connectionPool.maxIdleConnections,
        okHttpClientProperties.connectionPool.keepAliveDurationMs.toLong(),
        MILLISECONDS
      ))
      retryOnConnectionFailure(okHttpClientProperties.isRetryOnConnectionFailure)
    }
    .build()

  @Bean
  fun retrofitLoggingInterceptor(@Value("\${retrofit2.log-level:BASIC}") retrofitLogLevel: String) =
    HttpLoggingInterceptor().apply {
      level = HttpLoggingInterceptor.Level.valueOf(retrofitLogLevel)
    }
}
