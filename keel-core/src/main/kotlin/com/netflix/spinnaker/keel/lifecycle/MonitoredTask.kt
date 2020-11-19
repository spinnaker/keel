package com.netflix.spinnaker.keel.lifecycle

/**
 * Represents something that will be monitored.
 * For example, an orca task or a jenkins job.
 *
 * This 'task' is kicked off by a lifecycle event.
 * Events are emitted as the task is monitored based on that
 * initial event
 */
data class MonitoredTask(
  val triggeringEvent: LifecycleEvent,
  val link: String,
  val type: LifecycleEventType = triggeringEvent.type,
  val numFailures: Int = 0
)
