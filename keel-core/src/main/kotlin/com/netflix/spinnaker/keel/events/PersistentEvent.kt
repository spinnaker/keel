package com.netflix.spinnaker.keel.events

import java.time.Instant

abstract class PersistentEvent {
  abstract val scope: Scope
  abstract val application: String
  abstract val timestamp: Instant

  enum class Scope {
    RESOURCE,
    APPLICATION
  }
}
