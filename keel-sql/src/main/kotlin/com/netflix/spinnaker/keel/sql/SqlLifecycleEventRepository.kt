package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.lifecycle.LifecycleEvent
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventRepository
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventScope
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType
import com.netflix.spinnaker.keel.lifecycle.LifecycleStep
import org.jooq.DSLContext
import java.time.Clock

class SqlLifecycleEventRepository(
  private val clock: Clock,
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry
) : LifecycleEventRepository {
  override fun saveEvent(event: LifecycleEvent) {
    TODO("not implemented")
  }

  // todo: implement
  override fun getEvents(artifactId: String, artifactVersion: String): List<LifecycleEvent> =
    emptyList()

  override fun getEvent(
    scope: LifecycleEventScope,
    type: LifecycleEventType,
    artifact: DeliveryArtifact,
    artifactVersion: String,
    id: String
  ) : LifecycleEvent {
    TODO("not implemented")
  }

  override fun getSteps(artifactId: String, artifactVersion: String): List<LifecycleStep> {
    TODO("not implemented")
  }

  override fun deleteEvent(
    scope: LifecycleEventScope,
    type: LifecycleEventType,
    artifact: DeliveryArtifact,
    artifactVersion: String,
    id: String
  ) {
    TODO("not implemented")
  }
}
