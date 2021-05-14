package com.netflix.spinnaker.keel.front50

import com.netflix.spinnaker.keel.core.api.DEFAULT_SERVICE_ACCOUNT
import com.netflix.spinnaker.keel.front50.model.Application
import com.netflix.spinnaker.keel.front50.model.Delivery
import com.netflix.spinnaker.keel.front50.model.Pipeline
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface Front50Service {

  @GET("/deliveries/{id}")
  suspend fun deliveryById(
    @Path("id") id: String,
    @Header("X-SPINNAKER-USER") user: String = DEFAULT_SERVICE_ACCOUNT
  ): Delivery

  @GET("/v2/applications/{name}")
  suspend fun applicationByName(
    @Path("name") name: String,
    @Header("X-SPINNAKER-USER") user: String = DEFAULT_SERVICE_ACCOUNT
  ): Application

  @GET("/v2/applications")
  suspend fun allApplications(
    @Header("X-SPINNAKER-USER") user: String = DEFAULT_SERVICE_ACCOUNT
  ): List<Application>

  @GET("/pipelines/{application}")
  suspend fun pipelinesByApplication(
    @Path("application") application: String,
    @Header("X-SPINNAKER-USER") user: String = DEFAULT_SERVICE_ACCOUNT
  ): List<Pipeline>

  @GET("/pipelines/{id}/history")
  suspend fun pipelineHistory(
    @Path("id") id: String,
    @Query("limit") limit: Int = 50,
    @Header("X-SPINNAKER-USER") user: String = DEFAULT_SERVICE_ACCOUNT
  ): List<Pipeline>
}
