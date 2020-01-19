package com.netflix.spinnaker.keel.echo

import com.netflix.spinnaker.keel.echo.model.EchoNotification
import retrofit2.http.Body
import retrofit2.http.POST

interface EchoService {
  @POST("/interactive-notifications")
  suspend fun sendInteractiveNotification(
    @Body notification: EchoNotification
  )
}
