package com.netflix.spinnaker.keel.api.ec2

data class ActiveServerGroupImage(
  val imageId: String,
  val appVersion: String?,
  val baseImageVersion: String?,
  val name: String,
  val imageLocation: String,
  val description: String?
)
