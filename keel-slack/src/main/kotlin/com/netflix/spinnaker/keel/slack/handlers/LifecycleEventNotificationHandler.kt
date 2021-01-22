package com.netflix.spinnaker.keel.slack.handlers

import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType
import com.netflix.spinnaker.keel.notifications.NotificationType
import com.netflix.spinnaker.keel.slack.SlackLifecycleNotification
import com.netflix.spinnaker.keel.slack.SlackService
import com.slack.api.model.kotlin_extension.block.withBlocks
import org.apache.logging.log4j.util.Strings
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Sends notification for a lifecycle event, like bake / build failures
 */
@Component
class LifecycleEventNotificationHandler (
  private val slackService: SlackService,
  @Value("\${spinnaker.baseUrl}") private val spinnakerBaseUrl: String,
  private val gitDataGenerator: GitDataGenerator
) : SlackNotificationHandler<SlackLifecycleNotification> {

 // override val type: NotificationType = NotificationType.LIFECYCLE_EVENT

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun sendMessage(notification: SlackLifecycleNotification, channel: String) {
    log.debug("Sending pinned artifact notification for application ${notification.application}")

    with(notification) {
      val imageUrl = when (type) {
          LifecycleEventType.BAKE -> "https://raw.githubusercontent.com/gcomstock/managed.delivery/master/src/icons/bake_fail.png"
          LifecycleEventType.BUILD -> "https://raw.githubusercontent.com/gcomstock/managed.delivery/master/src/icons/build_fail.png"
          else -> Strings.EMPTY
      }

      val artifactUrl = "$spinnakerBaseUrl/#/applications/${application}/environments/${artifact.reference}/${artifact.version}"

      val blocks = withBlocks {
        header {
          text("${type.name} failed", emoji = true)
        }

        section {
          markdownText("*Version:* <$artifactUrl|#${artifact.buildMetadata?.number}> " +
            "by @${artifact.gitMetadata?.author}\n " +
            "${artifact.gitMetadata?.commitInfo?.message}")
          accessory {
            image(imageUrl = imageUrl, altText = "lifecycle")
          }
        }

        section {
          gitDataGenerator.generateData(this, application, artifact)
        }

      }
      slackService.sendSlackNotification(channel, blocks)
    }
  }

}
