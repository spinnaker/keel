package com.netflix.spinnaker.keel.events

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Clock
import java.time.Instant
import com.netflix.spinnaker.keel.events.EventLevel.WARNING

@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  property = "type",
  include = JsonTypeInfo.As.PROPERTY
)
@JsonSubTypes(
  JsonSubTypes.Type(value = ApplicationActuationPaused::class, name = "ApplicationActuationPaused"),
  JsonSubTypes.Type(value = ApplicationActuationResumed::class, name = "ApplicationActuationResumed")
)
abstract class ApplicationEvent(
  override val triggeredBy: String? = null
) : PersistentEvent() {
  override val scope = EventScope.APPLICATION

  override val ref: String
    get() = application
}

/**
 * Actuation at the application level has been paused.
 *
 * @property reason The reason why actuation was paused.
 */
data class ApplicationActuationPaused(
  override val application: String,
  override val timestamp: Instant,
  override val triggeredBy: String?,
  override val level: EventLevel = WARNING,
  override val displayName: String = "Application management paused",
) : ApplicationEvent(), ResourceHistoryEvent {
  @JsonIgnore
  override val ignoreRepeatedInHistory = true

  constructor(application: String, triggeredBy: String, clock: Clock = Companion.clock) : this(
    application,
    clock.instant(),
    triggeredBy
  )
}

/**
 * Actuation at the application level has resumed.
 */
data class ApplicationActuationResumed(
  override val application: String,
  override val triggeredBy: String?,
  override val timestamp: Instant,
  override val displayName: String = "Application management resumed",
) : ApplicationEvent(), ResourceHistoryEvent {
  @JsonIgnore override val ignoreRepeatedInHistory = true

  constructor(application: String, triggeredBy: String, clock: Clock = Companion.clock) : this(
    application,
    triggeredBy,
    clock.instant()
  )
}
