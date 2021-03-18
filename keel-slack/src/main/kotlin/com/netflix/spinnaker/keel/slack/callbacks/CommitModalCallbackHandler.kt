package com.netflix.spinnaker.keel.slack.callbacks

import com.netflix.spinnaker.keel.slack.handlers.GitDataGenerator
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload
import com.slack.api.model.view.View
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A handler that builds the modal for launching the full commit modal
 */
@Component
class CommitModalCallbackHandler(
  private val gitDataGenerator: GitDataGenerator
) {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  fun buildView(slackCallbackResponse: BlockActionPayload): View {
    val message = slackCallbackResponse.actions.first().value
    val hash = slackCallbackResponse.actions.first().actionId.split(":")[1]
    return gitDataGenerator.buildFullCommitModal(message = message, hash = hash)
  }
}
