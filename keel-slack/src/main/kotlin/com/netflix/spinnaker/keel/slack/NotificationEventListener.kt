package com.netflix.spinnaker.keel.slack

import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.NotificationType
import com.netflix.spinnaker.keel.core.api.PromotionStatus
import com.netflix.spinnaker.keel.events.ApplicationActuationPaused
import com.netflix.spinnaker.keel.events.ApplicationActuationResumed
import com.netflix.spinnaker.keel.events.MarkAsBadNotification
import com.netflix.spinnaker.keel.events.PinnedNotification
import com.netflix.spinnaker.keel.events.UnpinnedNotification
import com.netflix.spinnaker.keel.lifecycle.LifecycleEvent
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.slack.handlers.SlackNotificationHandler
import com.netflix.spinnaker.keel.slack.handlers.supporting
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import com.netflix.spinnaker.keel.notifications.NotificationType as Type
/**
 * Responsible to listening to notification events, and fetching the information needed
 * for sending a notification, based on NotificationType.
 */
@Component
class NotificationEventListener(
  private val repository: KeelRepository,
  private val clock: Clock,
  private val handlers: List<SlackNotificationHandler<*>>
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @EventListener(PinnedNotification::class)
  fun onPinnedNotification(notification: PinnedNotification) {
    with(notification) {
      val deliveryArtifact = config.artifacts.find {
        it.reference == pin.reference
      } ?: return

      val pinnedArtifact = repository.getArtifactVersion(deliveryArtifact, pin.version, null)
      val currentArtifact = repository.getArtifactVersionByPromotionStatus(config, pin.targetEnvironment, deliveryArtifact, PromotionStatus.CURRENT)

      if (pinnedArtifact == null || currentArtifact == null) {
        log.debug("can't send notification as either pinned artifact or current artifacts information is missing")
        return
      }

      config.environments.first { environment ->
        environment.name == pin.targetEnvironment
      }.also {
        it.sendSlackMessage(SlackPinnedNotification(
          pin = pin,
          currentArtifact = currentArtifact,
          pinnedArtifact = pinnedArtifact,
          application = config.application,
          time = clock.instant()
        ),
        Type.ARTIFACT_PINNED)
      }
    }
  }


  @EventListener(UnpinnedNotification::class)
  fun onUnpinnedNotification(notification: UnpinnedNotification) {
    with(notification) {
      if (pinnedEnvironment == null) {
        log.debug("no pinned artifacts exists for application ${config.application} and environment $targetEnvironment")
        return
      }

      val latestApprovedArtifactVersion = repository.latestVersionApprovedIn(config, pinnedEnvironment!!.artifact, targetEnvironment)
      if (latestApprovedArtifactVersion == null) {
        log.debug("latestApprovedArtifactVersion is null for application ${config.application}, env $targetEnvironment. Can't send UnpinnedNotification")
        return
      }

      val latestArtifact = repository.getArtifactVersion(pinnedEnvironment!!.artifact, latestApprovedArtifactVersion, null)
      val pinnedArtifact = repository.getArtifactVersion(pinnedEnvironment!!.artifact, pinnedEnvironment!!.version, null)

      config.environments.first { environment ->
        environment.name == targetEnvironment
      }.also {
        it.sendSlackMessage(SlackUnpinnedNotification(
          latestArtifact = latestArtifact,
          pinnedArtifact = pinnedArtifact,
          application = config.application,
          time = clock.instant(),
          user = user,
          targetEnvironment = targetEnvironment
        ),
        Type.ARTIFACT_UNPINNED)
      }
    }
  }

  @EventListener(MarkAsBadNotification::class)
  fun onMarkAsBadNotification(notification: MarkAsBadNotification) {
    with(notification) {
      val deliveryArtifact = config.artifacts.find {
        it.reference == veto.reference
      } ?: return

      val vetoedArtifact = repository.getArtifactVersion(deliveryArtifact, veto.version, null)
      if (vetoedArtifact == null) {
        log.debug("vetoedArtifact is null for application ${config.application}. Can't send MarkAsBadNotification notification")
        return
      }

      config.environments.first { environment ->
        environment.name == veto.targetEnvironment
      }.also {
        it.sendSlackMessage(
          SlackMarkAsBadNotification(
            vetoedArtifact = vetoedArtifact,
            user = user,
            targetEnvironment = veto.targetEnvironment,
            time = clock.instant(),
            application = config.name,
            comment = veto.comment
          ),
          Type.ARTIFACT_MARK_AS_BAD
        )
      }
    }
  }

  @EventListener(ApplicationActuationPaused::class)
  fun onApplicationActuationPaused(notification: ApplicationActuationPaused) {
    with(notification) {
      val config = repository.getDeliveryConfigForApplication(application)

      config.environments.forEach { environment ->
        environment.sendSlackMessage(SlackPausedNotification(
          user = triggeredBy,
          time = clock.instant(),
          application = application
        ),
        Type.APPLICATION_PAUSED)
      }
    }
  }

  @EventListener(ApplicationActuationResumed::class)
  fun onApplicationActuationResumed(notification: ApplicationActuationResumed) {
    with(notification) {
      val config = repository.getDeliveryConfigForApplication(application)

      config.environments.forEach { environment ->
        environment.sendSlackMessage(SlackResumedNotification(
          user = triggeredBy,
          time = clock.instant(),
          application = application
        ),
          Type.APPLICATION_RESUMED)
      }
    }
  }

  @EventListener(LifecycleEvent::class)
  fun onLifecycleEvent(notification: LifecycleEvent) {
    with(notification) {
      val config = repository.getDeliveryConfig(artifactRef.split(":")[0])
      val deliveryArtifact = config.artifacts.find {
        it.reference == artifactRef.split(":")[1]
      } ?: return

      val artifact = repository.getArtifactVersion(deliveryArtifact, artifactVersion, null)
      if (artifact == null) {
        log.debug("vetoedArtifact is null for application ${config.application}. Can't send MarkAsBadNotification notification")
        return
      }

      config.environments.forEach { environment ->
        //We only notifying when failure happens
        if (status == LifecycleEventStatus.FAILED) {
          environment.sendSlackMessage(SlackLifecycleNotification(
            time = clock.instant(),
            artifact = artifact,
            type = type,
            application = config.application
          ),
          Type.LIFECYCLE_EVENT)
        }
      }
    }
  }


  private inline fun <reified T : SlackNotificationEvent> Environment.sendSlackMessage(message: T, type: Type) {
    val handler: SlackNotificationHandler<T>? = handlers.supporting(type)

    if (handler == null) {
      log.debug("no handler was found for notification type ${T::class.java}. Can't send slack notification.")
      return
    }

    this.notifications.filter {
      it.type == NotificationType.slack
    }.forEach {
      handler.sendMessage(message, it.address)
    }
  }
}

