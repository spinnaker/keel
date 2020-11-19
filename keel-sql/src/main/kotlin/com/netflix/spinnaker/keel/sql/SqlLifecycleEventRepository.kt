package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.lifecycle.LifecycleEvent
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventRepository
import com.netflix.spinnaker.keel.lifecycle.LifecycleStep
import com.netflix.spinnaker.keel.lifecycle.isEndingStatus
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.LIFECYCLE
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import de.huxhorn.sulky.ulid.ULID
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset.UTC
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
      val id = event.id ?: ULID().nextULID(clock.millis()) //random if not given
      jooq.insertInto(LIFECYCLE)
        .set(LIFECYCLE.UID, ULID().nextULID(clock.millis()))
        .set(LIFECYCLE.SCOPE, event.scope.name)
        .set(LIFECYCLE.REF, event.artifactRef)
        .set(LIFECYCLE.ARTIFACT_VERSION, event.artifactVersion)
        .set(LIFECYCLE.TYPE, event.type.name)
        .set(LIFECYCLE.ID, id)
        .set(LIFECYCLE.STATUS, event.status.name)
        .set(LIFECYCLE.TIMESTAMP, clock.timestamp())
        .set(LIFECYCLE.JSON, objectMapper.writeValueAsString(event))
        .execute()
    }
  }

  override fun getEvents(artifact: DeliveryArtifact, artifactVersion: String): List<LifecycleEvent> {
    return sqlRetry.withRetry(READ) {
      jooq.select(LIFECYCLE.JSON, LIFECYCLE.TIMESTAMP)
        .from(LIFECYCLE)
        .where(LIFECYCLE.REF.eq(artifact.toLifecycleRef()))
        .and(LIFECYCLE.ARTIFACT_VERSION.eq(artifactVersion))
        .orderBy(LIFECYCLE.TIMESTAMP.asc()) // oldest first
        .fetch()
        .map { (json, timestamp) ->
          val event = objectMapper.readValue<LifecycleEvent>(json)
          event.copy(timestamp = timestamp.toInstant(UTC))
        }
    }
  }

  /**
   * Sorts events into chronological groups by id then time.
   * Batches each group of id up into a summary by 'replaying' each event.
   *
   * @return a list of steps sorted in ascending order (oldest start time first)
   */
  override fun getSteps(artifact: DeliveryArtifact, artifactVersion: String): List<LifecycleStep> {
    val events = getEvents(artifact, artifactVersion)
      .sortedBy { it.id }
    val steps: Deque<LifecycleStep> = LinkedList()

    events.forEach { event ->
      var lastStep = steps.pollFirst()
      if (sameStep(event, lastStep)) {
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
        if (lastStep != null) {
          steps.push(lastStep)
        }
        val step = event.toStep()
        steps.push(step)
      }
    }

    return steps.toList().sortedBy { it.startTime }
  }

  private fun sameStep(event: LifecycleEvent, step: LifecycleStep?): Boolean =
    step != null && event.id == step.id && event.scope == step.scope && event.type == step.type
}
