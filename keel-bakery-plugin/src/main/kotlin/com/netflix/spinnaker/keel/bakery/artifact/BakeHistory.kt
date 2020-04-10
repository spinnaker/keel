package com.netflix.spinnaker.keel.bakery.artifact

interface BakeHistory {
  fun contains(appVersion: String, baseAmiVersion: String): Boolean
  fun add(appVersion: String, baseAmiVersion: String, regions: Collection<String>, taskId: String)
}
