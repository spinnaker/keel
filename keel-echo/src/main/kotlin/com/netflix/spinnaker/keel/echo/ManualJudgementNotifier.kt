package com.netflix.spinnaker.keel.echo

import com.netflix.spinnaker.config.KeelNotificationConfig
import com.netflix.spinnaker.keel.api.NotificationConfig
import com.netflix.spinnaker.keel.api.ScmInfo
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.api.events.ConstraintStateChanged
import com.netflix.spinnaker.keel.artifacts.generateCompareLink
import com.netflix.spinnaker.keel.core.api.ManualJudgementConstraint
import com.netflix.spinnaker.keel.core.api.PromotionStatus
import com.netflix.spinnaker.keel.echo.model.EchoNotification
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.kork.exceptions.SystemException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
@Configuration
@EnableConfigurationProperties(KeelNotificationConfig::class)
/**
 * Listens for [ConstraintStateChanged] events where the constraint is a [ManualJudgementConstraint] and sends
 * out notifications so that users can take action.
 */
class ManualJudgementNotifier(
  private val keelNotificationConfig: KeelNotificationConfig,
  private val echoService: EchoService,
  private val repository: KeelRepository,
  private val scmInfo: ScmInfo,
  @Value("\${spinnaker.baseUrl}") private val spinnakerBaseUrl: String
) {
  companion object {
    const val MANUAL_JUDGEMENT_DOC_URL =
      "https://www.spinnaker.io/guides/user/managed-delivery/environment-constraints/#manual-judgement"
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @EventListener(ConstraintStateChanged::class)
  fun constraintStateChanged(event: ConstraintStateChanged) {
    log.debug("Received constraint state changed event: $event")
    // if this is the first time the constraint was evaluated, send a notification
    // so the user can react via other interfaces outside the UI (e.g. e-mail, Slack)
    if (event.constraint is ManualJudgementConstraint &&
      event.previousState == null &&
      event.currentState.status == ConstraintStatus.PENDING
    ) {
      event.environment.notifications.map {
        // TODO: run in parallel
        runBlocking {
          log.debug("Sending notification for manual judgement with config $it")
          echoService.sendNotification(event.toEchoNotification(it))
        }
      }
    }
  }

  private fun ConstraintStateChanged.toEchoNotification(config: NotificationConfig): EchoNotification {
    val artifact = currentState.artifactReference?.let {
      repository.getArtifact(currentState.deliveryConfigName, it)
    } ?: throw SystemException("Required artifact reference missing in constraint state object")

    val deliveryConfig = repository.getDeliveryConfig(currentState.deliveryConfigName)
    val artifactUrl = "$spinnakerBaseUrl/#/applications/${deliveryConfig.application}/environments/${artifact.reference}/${currentState.artifactVersion}"
    val normalizedVersion = currentState.artifactVersion.removePrefix("${artifact.name}-")
    val currentDeployableArtifact = repository.getArtifactVersion(artifact, currentState.artifactVersion, null)
    val gitMetadata = currentDeployableArtifact
      ?.gitMetadata
    val currentArtifactInEnvironment = repository.getArtifactVersionByPromotionStatus(deliveryConfig, currentState.environmentName , artifact, PromotionStatus.CURRENT)

    var details = ""

    if (gitMetadata != null) {
      if (!gitMetadata.commitInfo?.message.isNullOrEmpty()) {
        details += "*Message:* ${gitMetadata.commitInfo!!.message}\n"
      }

      if (currentArtifactInEnvironment?.gitMetadata != null) {
        try {
          val compareLink = generateCompareLink(scmInfo, currentDeployableArtifact, currentArtifactInEnvironment, artifact)
          if (compareLink != null) {
            details += "<$compareLink|*See changes*>\n"
          }
        } catch (ex: Exception) {
          log.warn("Can't create comparable link for artifact ${currentArtifactInEnvironment.version}", ex)
        }
      }

      if (gitMetadata.project != null && gitMetadata.repo?.name != null) {
        details += if (gitMetadata.repo!!.link.isNullOrEmpty()) {
          "*Repo:* ${gitMetadata.project}/${gitMetadata.repo!!.name}"
        } else {
          "*Repo:* <${gitMetadata.repo!!.link}|${gitMetadata.project}/${gitMetadata.repo!!.name}>"
        }

        details += if (gitMetadata.branch != null) {
          ", *Branch:* ${gitMetadata.branch}\n"
        } else {
          "\n"
        }
      }

      if (!gitMetadata.author.isNullOrEmpty()) {
        details += "*Author:* ${gitMetadata.author}, "
      }

      details += if (gitMetadata.commitInfo?.link.isNullOrEmpty()) {
        "*Commit:* ${gitMetadata.commit}\n"
      } else {
        "*Commit:* <${gitMetadata.commitInfo!!.link}|${gitMetadata.commit}>\n"
      }
    }

    details +=
      "*Version:* <$artifactUrl|$normalizedVersion>\n" +
      "*Application:* ${deliveryConfig.application}, *Environment:* ${currentState.environmentName}\n"

    if (!keelNotificationConfig.enabled) {
      details += "<br/>Please consult the <$MANUAL_JUDGEMENT_DOC_URL|documentation> on how to approve the deployment."
    }
    val interactiveActions = mutableListOf(
      EchoNotification.ButtonAction(
        name = "manual-judgement",
        label = "Approve",
        value = ConstraintStatus.OVERRIDE_PASS.name
      ),
      EchoNotification.ButtonAction(
        name = "manual-judgement",
        label = "Reject",
        value = ConstraintStatus.OVERRIDE_FAIL.name
      ),
    )

    return EchoNotification(
      notificationType = EchoNotification.Type.valueOf(config.type.name.toUpperCase()),
      to = listOf(config.address),
      severity = EchoNotification.Severity.NORMAL,
      source = EchoNotification.Source(
        application = deliveryConfig.application
      ),
      additionalContext = mapOf(
        "formatter" to "MARKDOWN",
        "subject" to "Manual artifact promotion approval",
        "body" to
          ":warning: Manual approval required to deploy artifact *${artifact.name}*\n" + details
      ),
      interactiveActions = if (keelNotificationConfig.enabled) {
        EchoNotification.InteractiveActions(
          callbackServiceId = "keel",
          callbackMessageId = currentState.uid?.toString() ?: error("ConstraintState.uid not present"),
          actions = interactiveActions,
          color = "#fcba03"
        )
      } else {
        null
      }
    )
  }
}
