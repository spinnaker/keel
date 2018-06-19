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
package com.netflix.spinnaker.keel.front50

import com.netflix.spinnaker.keel.Asset
import com.netflix.spinnaker.keel.AssetSpec
import com.netflix.spinnaker.keel.AssetStatus
import com.netflix.spinnaker.keel.front50.model.Application
import com.netflix.spinnaker.keel.front50.model.PipelineConfig
import retrofit.client.Response
import retrofit.http.*

interface Front50Service {

  @GET("/intents")
  fun getIntents(): List<Asset<AssetSpec>>

  @GET("/intents")
  fun getIntentsByStatus(@Query("status") statuses: List<AssetStatus>?): List<Asset<AssetSpec>>

  @GET("/intents/{id}")
  fun getIntent(@Path("id") id: String): Asset<AssetSpec>

  @POST("/intents")
  fun upsertIntent(@Body asset: Asset<AssetSpec>): Asset<AssetSpec>

  @DELETE("/intents/{id}")
  fun deleteIntent(@Path("id") id: String): Response

  @GET("/v2/applications/{applicationName}")
  fun getApplication(@Path("applicationName") applicationName: String): Application

  @GET("/pipelines/{applicationName}")
  fun getPipelineConfigs(@Path("applicationName") applicationName: String): List<PipelineConfig>
}
