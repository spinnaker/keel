package com.netflix.spinnaker.keel.lifecycle

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import java.time.Instant

/**
 * Something that happens in the lifecycle of an artifact.
 * This step usually has a beginning, end, and transitions through statuses.
 *
 * Events are identified by the artifact, version, scope, type, and id.
 * We create an id for the artifact by calling [artifact.toLifecycleEventId()].
 *
 * [scope] and [type] control where the event is shown, right now there is only one of each.
 * [id] is a caller-provided id: if the caller wants to be able to update the event,
 *  it should not be random. If the event is fire and forget, the id can be null.
 * [link] stores a link that will be surfaced in a clickable button.
 * [text] is the text for the even that is shown to the user.
 */
data class LifecycleStep(
  val scope: LifecycleEventScope,
  val type: LifecycleEventType,
  val id: String?,
  val status: LifecycleEventStatus,
  val text: String?,
  val link: String?,
  val startTime: Instant? = null,
  val endTime: Instant? = null,
)
