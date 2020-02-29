package com.netflix.spinnaker.keel.events

import java.time.Clock
import java.time.Instant

abstract class PersistentEvent {
  abstract val scope: Scope
  abstract val application: String
  abstract val timestamp: Instant

  companion object {
    val clock: Clock = Clock.systemDefaultZone()
  }

  enum class Scope {
    RESOURCE,
    APPLICATION
  }
}
