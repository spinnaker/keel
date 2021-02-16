package com.netflix.spinnaker.keel.slack

import com.netflix.spectator.api.BasicTag
import com.netflix.spectator.api.Counter
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.config.SlackConfiguration
import com.netflix.spinnaker.keel.notifications.NotificationType
import com.slack.api.Slack
import com.slack.api.model.block.LayoutBlock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * This notifier is responsible for actually sending or fetching data from Slack directly.
 */
@Component
@EnableConfigurationProperties(SlackConfiguration::class)
class SlackService(
  private val springEnv: Environment,
  final val slackConfig: SlackConfiguration,
  private val spectator: Registry
) {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  private val configToken = slackConfig.token
  private val slack = Slack.getInstance()

  private val isSlackEnabled: Boolean
    get() = springEnv.getProperty("keel.notifications.slack", Boolean::class.java, true)

  /**
   * Sends slack notification to [channel], which the specified [blocks].
   * In case of an error with creating the blocks, or for notification preview, the fallback text will be sent.
   */
  fun sendSlackNotification(channel: String, blocks: List<LayoutBlock>,
                            application: String, type: List<NotificationType>,
                            fallbackText: String) {
    if (isSlackEnabled) {
      log.debug("Sending slack notification $type for application $application in channel $channel")

      val response = slack.methods(configToken).chatPostMessage { req ->
        req
          .channel(channel)
          .blocks(blocks)
          .text(fallbackText)
      }

      if (response.isOk) {
        spectator.counter(
          SLACK_MESSAGE_SENT,
          listOf(
            BasicTag("notificationType", type.first().name),
            BasicTag("application", application)
          )
        ).safeIncrement()
      }

      if (!response.isOk) {
        log.warn("slack couldn't send the notification. error is: ${response.error}")
        return
      }

      log.debug("slack notification $type for application $application and channel $channel was successfully sent.")

    } else {
      log.debug("new slack integration is not enabled")
    }
  }

  /**
   * Get slack username by the user's [email]. Return the original email if username is not found.
   */
  fun getUsernameByEmail(email: String): String {
    log.debug("lookup user id for email $email")
    val response = slack.methods(configToken).usersLookupByEmail { req ->
      req.email(email)
    }

    if (!response.isOk) {
      log.warn("slack couldn't get username by email. error is: ${response.error}")
      return email
    }

    if (response.user != null && response.user.name != null) {
      return "@${response.user.name}"
    }
    return email
  }

  /**
   * Get user's email address by slack [userId]. Return the original userId if email is not found.
   */
  fun getEmailByUserId(userId: String): String {
    log.debug("lookup user email for username $userId")
    val response = slack.methods(configToken).usersInfo { req ->
      req.user(userId)
    }

    if (!response.isOk) {
      log.warn("slack couldn't get email by user id. error is: ${response.error}")
      return userId
    }

    log.debug("slack getEmailByUserId returned ${response.isOk}")

    if (response.user != null && response.user.profile.email != null) {
      return response.user.profile.email
    }
    return userId
  }


  companion object {
    private const val SLACK_MESSAGE_SENT = "keel.slack.message.sent"
  }

  private fun Counter.safeIncrement() =
    try {
      increment()
    } catch (ex: Exception) {
      log.error("Exception incrementing {} counter: {}", id().name(), ex.message)
    }
}
