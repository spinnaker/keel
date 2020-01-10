package com.netflix.spinnaker.keel.persistence.memory

import com.netflix.spinnaker.keel.persistence.TaskTrackingRepositoryTests

class InMemoryTaskTrackingRepositoryTests : TaskTrackingRepositoryTests<InMemoryTaskTrackingRepository>() {
  override fun factory(): InMemoryTaskTrackingRepository {
    return InMemoryTaskTrackingRepository()
  }
}
