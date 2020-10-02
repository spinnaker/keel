package com.netflix.spinnaker.keel.notifications

data class NotificationMessage(
  val subject: String,
  val body: String,
  val color: String = "#cccccc"
)