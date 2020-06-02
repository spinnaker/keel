package com.netflix.spinnaker.igor

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Igor methods related to Source Control Management (SCM) operations.
 */
interface ScmService {
  @GET("/delivery-config/manifest")
  suspend fun getDeliveryConfigManifest(
    @Query("scmType") repoType: String,
    @Query("project") projectKey: String,
    @Query("repository") repositorySlug: String,
    @Query("manifest") manifestPath: String,
    @Query("ref") ref: String? = null
  ): Map<String, Any?>
}
