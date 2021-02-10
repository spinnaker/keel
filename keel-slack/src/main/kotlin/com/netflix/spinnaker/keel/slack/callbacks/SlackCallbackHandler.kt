package com.netflix.spinnaker.keel.slack.callbacks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.notifications.NotificationType.MANUAL_JUDGMENT
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.kork.web.exceptions.InvalidRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URLDecoder

@Component
class SlackCallbackHandler(
  private val manualJudgmentCallbackHandler: ManualJudgmentCallbackHandler
) {
  companion object {
    val mapper: ObjectMapper = configuredObjectMapper()
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  fun handleCallback(request: String?) {
    if (request == null) {
      log.error("the request body coming from slack callback is null.")
      //throw exception?
      return
    }

    val payload = parseSlackPayload(request)
    //payload can be null if a user is clicking on "More" / "See changes" buttons
    if (payload == null) {
      log.debug("slack payload is null -- it's probably a user clicking on a button without any action needed")
      return
    }

    //we can add more callback handlers here based on notification type if needed
    when (payload.notificationType) {
      MANUAL_JUDGMENT.name -> {
        manualJudgmentCallbackHandler.handleMJResponse(payload)
      }
      else -> {
        log.warn("no suitable callback handler was found for type ${payload.notificationType}")
        return
      }
    }
  }


  fun parseSlackPayload(body: String): SlackCallbackResponse? {
    if (!body.startsWith("payload=")) {
      throw InvalidRequestException("Missing payload field in Slack callback request.")
    }
    return try {
      mapper.readValue<SlackCallbackResponse>(
        // Slack requests use application/x-www-form-urlencoded
        URLDecoder.decode(body.split("payload=")[1], "UTF-8"))
    } catch (ex: Exception) {
      log.warn("error accrued when converting slack callback response to SlackCallbackResponse object.", ex)
      return null
    }
  }


  data class SlackCallbackResponse(
    val type: String,
    val user: SlackUser,
    val actions: List<SlackAction>,
    val response_url: String,
    val message: Map<String, Any>
  )

  data class SlackUser(
    val username: String,
    val id: String,
    val name: String
  )

  data class SlackAction(
    val type: String,
    val action_id: String,
    val value: String,
    val action_ts: String
  )


  val SlackCallbackResponse.notificationType
    get() = actions.first().action_id.split(":").last()

}
