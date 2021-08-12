package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.Resource

interface FeatureRolloutRepository {
  fun markRolloutStarted(feature: String, resourceId: String)
  fun countRolloutAttempts(feature: String, resourceId: String): Int
}

fun FeatureRolloutRepository.markRolloutStarted(feature: String, resource: Resource<*>) =
  markRolloutStarted(feature, resource.id)

fun FeatureRolloutRepository.countRolloutAttempts(feature: String, resource: Resource<*>) =
  countRolloutAttempts(feature, resource.id)
