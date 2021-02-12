package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.slack.callbacks.ManualJudgmentCallbackHandler
import com.slack.api.app_backend.interactive_components.response.ActionResponse
import com.slack.api.bolt.App
import com.slack.api.bolt.servlet.SlackAppServlet
import org.springframework.stereotype.Component
import javax.servlet.annotation.WebServlet

@Component
@WebServlet("/slack/notifications/callbacks")
/**
 * New endpoint for the new slack integration. This will be called from gate directly instead of echo.
 */
class SlackAppController(
  slackApp: App,
  private val mj: ManualJudgmentCallbackHandler
) : SlackAppServlet(slackApp){
  init {
    //the pattern here should match the action id in the actual button, which is: constraintId:action:Manual_judgment
    val pattern = "^([a-zA-Z0-9_\\-.]+):([a-zA-Z0-9_\\-.]+):([a-zA-Z0-9_\\-.]+)".toPattern()
    slackApp.blockAction(pattern) { req, ctx ->
      if (req.payload.responseUrl != null) {
        mj.updateConstraintState(req.payload)
        val response = ActionResponse.builder()
          .blocks(mj.updateMJNotification(req.payload))
          .text(mj.fallbackText(req.payload))
          .replaceOriginal(true)
          .build()
        ctx.respond(response)
      }
      ctx.ack()
    }
  }
}
