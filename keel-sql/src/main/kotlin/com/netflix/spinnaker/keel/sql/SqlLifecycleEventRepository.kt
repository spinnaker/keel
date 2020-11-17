package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.lifecycle.LifecycleEvent
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventRepository
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventScope
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType
import com.netflix.spinnaker.keel.lifecycle.LifecycleStep
import com.netflix.spinnaker.keel.lifecycle.isEndingStatus
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.LIFECYCLE
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import de.huxhorn.sulky.ulid.ULID
import org.jooq.DSLContext
import java.time.Clock
import java.util.Deque
import java.util.LinkedList

class SqlLifecycleEventRepository(
  private val clock: Clock,
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry,
  private val objectMapper: ObjectMapper
) : LifecycleEventRepository {
  override fun saveEvent(event: LifecycleEvent) {
    sqlRetry.withRetry(WRITE) {
      jooq.insertInto(LIFECYCLE)
        .set(LIFECYCLE.UID, ULID().nextULID(clock.millis()))
        .set(LIFECYCLE.SCOPE, event.scope.name)
        .set(LIFECYCLE.REF, event.artifact.toLifecycleRef())
        .set(LIFECYCLE.ARTIFACT_VERSION, event.artifactVersion)
        .set(LIFECYCLE.TYPE, event.type.name)
        .set(LIFECYCLE.ID, event.id ?: ULID().nextULID(clock.millis())) //random if not given
        .set(LIFECYCLE.STATUS, event.status.name)
        .set(LIFECYCLE.TIMESTAMP, clock.timestamp())
        .set(LIFECYCLE.JSON, objectMapper.writeValueAsString(event))
        .execute()
    }
  }

  override fun getEvents(artifact: DeliveryArtifact, artifactVersion: String): List<LifecycleEvent> {
    return sqlRetry.withRetry(READ) {
      jooq.select(LIFECYCLE.JSON)
        .from(LIFECYCLE)
        .where(LIFECYCLE.REF.eq(artifact.toLifecycleRef()))
        .and(LIFECYCLE.ARTIFACT_VERSION.eq(artifactVersion))
        .orderBy(LIFECYCLE.TIMESTAMP.asc()) // oldest first
        .fetch()
        .map { (json) ->
          objectMapper.readValue<LifecycleEvent>(json)
        }
    }
  }

  override fun getEvent(
    scope: LifecycleEventScope,
    type: LifecycleEventType,
    artifact: DeliveryArtifact,
    artifactVersion: String,
    id: String
  ) : LifecycleEvent {
    TODO("not implemented")
  }

  override fun getSteps(artifact: DeliveryArtifact, artifactVersion: String): List<LifecycleStep> {
    val events = getEvents(artifact, artifactVersion)
    val steps: Deque<LifecycleStep> = LinkedList()

    events.forEach { event ->
      var lastStep = steps.pollFirst()
      if (sameStep(event, lastStep)) { // maybe we need to make sure this isn't a starting event also?
        lastStep = lastStep.copy(status = event.status)
        if (event.text != null) {
          lastStep = lastStep.copy(text = event.text)
        }
        if (event.link != null) {
          lastStep = lastStep.copy(link = event.link)
        }
        if (event.status.isEndingStatus()) {
          lastStep = lastStep.copy(endTime = event.timestamp)
        }
        steps.push(lastStep)
      } else {
        val step = event.toStep() //todo eb: probably other way around
        steps.push(step)
        // fill in text and link here? or somewhere else?
      }
    }

    /*
      todo eb:
        - think through all the statuses, what "ends" the event?
        - who adds the text for the event?
          is it in the event every time?
          is it a property in the subclass of the step?
        - events need to have other data
        - what if we get multiple start / middle / end events?
          is that an error? I'm not sure.
     */

    return steps.toList()
  }

  private fun sameStep(event: LifecycleEvent, step: LifecycleStep?): Boolean =
    step != null && event.id == step.id && event.scope == step.scope && event.type == step.type

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
