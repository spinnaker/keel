package com.netflix.spinnaker.keel.api.ec2

data class RedirectConfig(
  val protocol: String,
  val port: String?,
  val host: String,
  val path: String,
  val query: String?,
  val statusCode: String
)
