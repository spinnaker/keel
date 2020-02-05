package com.netflix.spinnaker.keel.echo

import com.netflix.spinnaker.keel.api.ConstraintStatus
import com.netflix.spinnaker.keel.api.ManualJudgementConstraint
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.echo.model.EchoNotification
import com.netflix.spinnaker.keel.events.ConstraintStateChanged
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
/**
 * Listens for [ConstraintStateChanged] events where the constraint is a [ManualJudgementConstraint] and sends
 * out notifications so that users can take action.
 */
class ManualJudgementNotifier(
  private val dynamicConfigService: DynamicConfigService,
  private val echoService: EchoService
) {

  companion object {
    const val INTERACTIVE_NOTIFICATIONS_ENABLED = "keel.constraints.manual-judgement.interactive-notifications"
  }

  @EventListener(ConstraintStateChanged::class)
  fun constraintStateChanged(event: ConstraintStateChanged) {
    // if this is the first time the constraint was evaluated, send a notification
    // so the user can react via other interfaces outside the UI (e.g. e-mail, Slack)
    if (event.constraint is ManualJudgementConstraint &&
      event.previousState == null &&
      event.currentState.status == ConstraintStatus.PENDING) {
      event.environment.notifications.map {
        // TODO: run in parallel
        runBlocking {
          echoService.sendNotification(event.toEchoNotification(it))
        }
      }
    }
  }

  private fun ConstraintStateChanged.toEchoNotification(config: NotificationConfig): EchoNotification {
    return EchoNotification(
      notificationType = EchoNotification.Type.valueOf(config.type.name.toUpperCase()),
      to = listOf(config.address),
      // templateGroup = TODO
      severity = EchoNotification.Severity.NORMAL,
      source = EchoNotification.Source(
        // FIXME: Environment should probably have a reference to the application name...
        application = environment.resources.firstOrNull()?.application
        // TODO: anything for executionType, executionId, user?
      ),
      additionalContext = mapOf(
        "formatter" to "MARKDOWN",
        "subject" to "Manual artifact promotion approval",
        "body" to
          ":warning: The artifact *${currentState.artifactVersion}* from delivery config " +
          "*${currentState.deliveryConfigName}* requires your manual approval for deployment " +
          "into the *${currentState.environmentName}* environment."
      ),
      interactiveActions = if (dynamicConfigService.isEnabled(INTERACTIVE_NOTIFICATIONS_ENABLED, false)) {
        EchoNotification.InteractiveActions(
          callbackServiceId = "keel",
          callbackMessageId = currentState.uid?.toString() ?: error("ConstraintState.uid not present"),
          actions = listOf(
            EchoNotification.ButtonAction(
              name = "manual-judgement",
              label = "Approve",
              value = ConstraintStatus.OVERRIDE_PASS.name
            ),
            EchoNotification.ButtonAction(
              name = "manual-judgement",
              label = "Reject",
              value = ConstraintStatus.OVERRIDE_FAIL.name
            )
          ),
          color = "#fcba03"
        )
      } else {
        null
      }
    )
  }
}
