package com.netflix.spinnaker.keel.slack.callbacks

import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.core.api.parseUID
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.slack.SlackService
import com.netflix.spinnaker.keel.slack.callbacks.SlackCallbackHandler.SlackCallbackResponse
import com.netflix.spinnaker.kork.exceptions.SystemException
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.kotlin_extension.block.withBlocks
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * This handler will handle slack callbacks coming from Manual Judgment notifications.
 * First, it will update the constraint status based on the user response (either approve/reject)
 * Second, it will construct an updated notification with the action preformed and the user who did it.
 */
@Component
class ManualJudgmentCallbackHandler(
  private val clock: Clock,
  private val repository: KeelRepository,
  private val slackService: SlackService
) {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  // Updating the constraint status based on the user response (either approve/reject)
  fun updateManualJudgementNotification(slackCallbackResponse: SlackCallbackResponse) {
    val constraintUid = slackCallbackResponse.constraintId
    val currentState = repository.getConstraintStateById(parseUID(constraintUid))
      ?: throw SystemException("constraint@callbackId=$constraintUid", "constraint not found")

    val user = slackService.getEmailByUserId(slackCallbackResponse.user.id)
    val actionStatus = slackCallbackResponse.actions.first().value

    log.debug(
      "Updating constraint status based on notification interaction: " +
        "user = $user, status = $actionStatus}"
    )

    repository
      .storeConstraintState(
        currentState.copy(
          status = ConstraintStatus.valueOf(actionStatus),
          judgedAt = Instant.now(),
          judgedBy = user
        )
      )

    // convert status to actual action --> like OVERRIDE_PASS to "approve"
    val actionPerformed = actionsMap[actionStatus]
    if (actionPerformed == null) {
      log.warn("can't map slack action status to an actual action and therefore can't send an updated notification.")
      return
    }

    respondToCallback(slackCallbackResponse, actionPerformed)
  }

  fun respondToCallback(slackCallbackResponse: SlackCallbackResponse, actionPerformed: String) {
    log.debug("Responding to Slack callback via ${slackCallbackResponse.response_url}")

    val fallbackText = "@${slackCallbackResponse.user.name} hit " +
      "$actionPerformed on <!date^${clock.instant().epochSecond}^{date_num} {time_secs}|fallback-text-include-PST>"

    //construct an update notification
    val updatedNotification = updateMJNotification(
      slackCallbackResponse.message["blocks"] as List<Any>,
      fallbackText,
      actionPerformed)

    //send notification using the response url slack provides with the original message
    slackService.respondToCallback(
      slackCallbackResponse.response_url,
      updatedNotification,
      fallbackText)
  }

  fun updateMJNotification(originalBlocks: List<Any>, fallbackText: String, action: String): List<LayoutBlock> {
    try {
      //This is pretty ugly, but currently slack SDK doesn't have a nice way to parse those fields natively
      val originalCommitText = ((originalBlocks[1] as Map<*, *>)["text"] as Map<*, *>)["text"] as String

      val originalGitInfo = originalBlocks[2] as Map<*, *>
      val originalGitInfoText = (originalGitInfo["text"] as Map<*, *>)["text"]
      val originalUrl = (originalGitInfo["accessory"] as Map<*, *>)["url"] as String

      return withBlocks {
        header {
          text("Was awaiting manual judgement", emoji = true)
        }
        section {
          //This is to mark the old text with strikeout
          markdownText("~${originalCommitText.replace("\n\n", "\n").replace("\n", "~\n~")}~")
          accessory {
            image("https://raw.githubusercontent.com/gcomstock/managed.delivery/master/src/icons/mj_was_needed.png", altText = "mj_done")
          }
        }
        section {
          markdownText("~$originalGitInfoText~")
          accessory {
            button {
              text("More...")
              actionId("button-action")
              url(originalUrl)
            }
          }
        }

        context {
          elements {
            markdownText(fallbackText)
          }
        }
      }

    } catch (ex: Exception) {
      log.debug("exception occurred while creating updated MJ notification. Will use a fallback text instead")
      return emptyList()
    }
  }

  val SlackCallbackResponse.constraintId
    get() = actions.first().action_id.split(":").first()

  val actionsMap: Map<String, String> =
    mapOf(
      ConstraintStatus.OVERRIDE_PASS.name to "approve",
      ConstraintStatus.OVERRIDE_FAIL.name to "reject")
}
