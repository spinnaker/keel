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
 * [artifactVersion] is the version of the artifact that this event pertains to.
 *
 * [scope] and [type] control where the event is shown, right now there is only one of each.
 * [id] is a caller-provided id: if the caller wants to be able to update the event,
 *  it should not be random. If the event is fire and forget, the id can be null.
 * [link] stores a link that will be surfaced in a clickable button.
 * [text] is the text for the even that is shown to the user.
 */
interface LifecycleStep { //LifecycleStatus? LifecycleStepStatus?
  val artifact: DeliveryArtifact
  val artifactVersion: String
  val scope: LifecycleEventScope
  val type: LifecycleEventType
  val id: String?
  val status: LifecycleEventStatus
  val text: String
  val link: String
  val startTime: Instant?
  val endTime: Instant?
}

/**
 * A specific lifecycle step with some of the details filled in.
 */
// todo eb: should this go in the bakery module?
data class BakeStep(
  override val artifact: DeliveryArtifact,
  override val artifactVersion: String,
  override val status: LifecycleEventStatus,
  override val link: String,
  override val id: String = "bake-$artifactVersion",
  override val text: String = "Bake: ${status.name.toLowerCase()}",
  override val scope: LifecycleEventScope = LifecycleEventScope.PRE_DEPLOYMENT,
  override val type: LifecycleEventType = LifecycleEventType.BAKE,
  override val startTime: Instant? = null,
  override val endTime: Instant? = null,
) : LifecycleStep
