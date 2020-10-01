package com.netflix.spinnaker.keel.echo

import com.netflix.spinnaker.config.KeelNotificationConfig
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.echo.model.EchoNotification
import com.netflix.spinnaker.keel.echo.model.EchoNotification.InteractiveActions
import com.netflix.spinnaker.keel.events.ResourceHealthEvent
import com.netflix.spinnaker.keel.events.ResourceNotificationEvent
import com.netflix.spinnaker.keel.notifications.Notifier.UNHEALTHY
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.NoSuchResourceException
import com.netflix.spinnaker.keel.persistence.NotifierRepository
import com.netflix.spinnaker.keel.persistence.OrphanedResourceException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * A class that forwards notifications for resources based on the environment
 * notification config.
 *
 * This class receives every notification, but notifies only once per
 *  notify.waiting-duration (default once per day) if the notification
 *  is a duplicate
 *
 * Note: this implementation is simple functionality. We could add more advanced
 *  functionality by allowing the message to contain whether we should notify
 *  more than once (and the frequency) and saving that into the database.
 *  We could save the notification and check the database periodically to see
 *  what needs to be sent, instead of relying on the events to keep coming.
 *  We could allow the event to contain the full echo notification instead of
 *  building it in this class, which would allow buttons and things in some
 *  messages.
 */
@Configuration
@EnableConfigurationProperties(KeelNotificationConfig::class)
@Component
class ResourceNotifier(
  private val echoService: EchoService,
  private val keelRepository: KeelRepository,
  private val notifierRepository: NotifierRepository,
  private val keelNotificationConfig: KeelNotificationConfig,
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @EventListener(ResourceNotificationEvent::class)
  fun onResourceNotificationEvent(event: ResourceNotificationEvent) {
    val shouldNotify = notifierRepository.addNotification(event.resourceId, event.notifier)
    if (shouldNotify) {
      notify(event)
      notifierRepository.markSent(event.resourceId, event.notifier)
    }
  }

  private fun notify(event: ResourceNotificationEvent) {
    log.debug("Sending notifications for resource ${event.resourceId} with content ${event.message}")
    try {
      val application = keelRepository.getResource(event.resourceId).application
      val env = keelRepository.environmentFor(event.resourceId)
      env.notifications.forEach { notificationConfig ->
        val notification = notificationConfig.toEchoNotification(application, event)
        log.debug("Sending notification for resource ${event.resourceId} with config $notification")
        runBlocking {
          echoService.sendNotification(notification)
        }
      }
    } catch (e: Exception) {
      when (e) {
        is NoSuchResourceException -> {
          log.error("Trying to notify resource ${event.resourceId} but it doesn't exist. Not notifying.")
          return
        }
        is OrphanedResourceException -> {
          log.error("Trying to notify resource ${event.resourceId} but it doesn't have an environment. Not notifying.")
          return
        }
        else -> throw e
      }
    }

  }

  private fun NotificationConfig.toEchoNotification(application: String, event: ResourceNotificationEvent): EchoNotification {
    return EchoNotification(
      notificationType = EchoNotification.Type.valueOf(type.name.toUpperCase()),
      to = listOf(address),
      severity = EchoNotification.Severity.NORMAL,
      source = EchoNotification.Source(
        application = application
      ),
      additionalContext = mapOf(
        "formatter" to "MARKDOWN",
        "subject" to event.message.subject,
        "body" to event.message.body
      ),
      interactiveActions = generateInteractiveConfig(event.resourceId, event.notifier.name)
    )
  }

  private fun generateInteractiveConfig(resourceId: String, notifier: String): InteractiveActions? =
    if (keelNotificationConfig.enabled) {
      InteractiveActions(
        callbackServiceId = "keel",
        callbackMessageId = "$resourceId:$notifier",
        actions = listOf()
      )
    } else {
      null
    }

  /**
   * Clears a notification for the unhealthy notifier,
   * if it exists
   */
  @EventListener(ResourceHealthEvent::class)
  fun onResourceHealthEvent(event: ResourceHealthEvent) {
    if (event.healthy) {
      notifierRepository.clearNotification(event.resourceId, UNHEALTHY)
    }
  }
}
