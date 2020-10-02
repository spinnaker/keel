package com.netflix.spinnaker.keel.veto.unhealthy

import com.netflix.spinnaker.keel.api.AccountAwareLocations
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.Monikered
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.events.NotifierMessage
import com.netflix.spinnaker.keel.events.ResourceHealthEvent
import com.netflix.spinnaker.keel.events.ResourceNotificationEvent
import com.netflix.spinnaker.keel.notifications.NotificationScope.RESOURCE
import com.netflix.spinnaker.keel.notifications.Notifier.UNHEALTHY
import com.netflix.spinnaker.keel.persistence.UnhealthyVetoRepository
import com.netflix.spinnaker.keel.veto.Veto
import com.netflix.spinnaker.keel.veto.VetoResponse
import com.netflix.spinnaker.keel.veto.friendlyDuration
import com.netflix.spinnaker.keel.veto.friendlyTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A veto that stops keel from checking a resource while it's unhealthy,
 * because there's nothing we can do to make it healthy
 */
@Component
class UnhealthyVeto(
  private val unhealthyVetoRepository: UnhealthyVetoRepository,
  @Value("\${veto.unhealthy.ignore-duration:PT5M}")
  private val ignoreDuration: String,
  private val clock: Clock,
  private val publisher: ApplicationEventPublisher,
  @Value("\${spinnaker.baseUrl}") private val spinnakerBaseUrl: String,
) : Veto {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override suspend fun check(resource: Resource<*>): VetoResponse {
    val resourceId = resource.id

    if (unhealthyVetoRepository.isHealthy(resourceId)) {
      return allowedResponse()
    }

    val lastAllowedTime = unhealthyVetoRepository.getLastAllowedTime(resourceId) ?: return allowedResponse()
    val now = clock.instant()
    val shouldAllow = now.minus(Duration.parse(ignoreDuration)) > lastAllowedTime

    return if (shouldAllow) {
      log.debug("Resource $resourceId has been ignored for ${friendlyTime(ignoreDuration)}, allowing a recheck.")
      unhealthyVetoRepository.markAllowed(resourceId)
      allowedResponse()
    } else {
      // if we no longer have the resource marked as unhealthy, allow it to be checked
      val timeNoticed = unhealthyVetoRepository.getNoticedTime(resource.id) ?: return allowedResponse()
      publisher.publishEvent(ResourceNotificationEvent(RESOURCE, resourceId, UNHEALTHY, message(resource, timeNoticed)))
      deniedResponse(message = "Resource is unhealthy with no diff, waiting ${friendlyTime(ignoreDuration)} before rechecking.", vetoArtifact = false)
    }
  }

  /**
   *  Assumption: health is only for clusters, and we have specific requirements
   *  about what the spec looks like in order to construct the notification link
   */
  private fun message(resource: Resource<*>, timeNoticed: Instant): NotifierMessage {
    val spec = resource.spec
    if (spec !is Monikered) {
      throw UnsupportedResourceTypeException("Resource kind ${resource.kind} must be monikered to construct resource links")
    }
    if (spec !is Locatable<*>) {
      throw UnsupportedResourceTypeException("Resource kind ${resource.kind} must be locatable to construct resource links")
    }
    val locations = spec.locations
    if (locations !is AccountAwareLocations) {
      throw UnsupportedResourceTypeException("Resource kind ${resource.kind} must be have account aware locations to construct resource links")
    }

    val params = ClusterViewParams(
      acct = locations.account,
      q = resource.spec.displayName,
      stack = spec.moniker.stack ?: "(none)",
      detail = spec.moniker.detail ?: "(none)"
    )
    val resourceUrl = "$spinnakerBaseUrl/#/applications/${resource.application}/clusters?${params.toURL()}"

    val durationUnheathy = Duration.between(timeNoticed, clock.instant())

    return NotifierMessage(
      subject = "${resource.spec.displayName} is unhealthy",
      body = "<$resourceUrl|${resource.id}> has been unhealthy for ${friendlyDuration(durationUnheathy)}. " +
        "Spinnaker does not detect any changes to this resource. " +
        "Manual intervention might be required. Please check the History view to see more details."
    )
  }

  @EventListener(ResourceHealthEvent::class)
  fun onResourceHealthEvent(event: ResourceHealthEvent) {
    if (event.healthy) {
      unhealthyVetoRepository.markHealthy(event.resourceId)
    } else {
      log.debug("Marking resource ${event.resourceId} as unhealthy")
      unhealthyVetoRepository.markUnhealthy(event.resourceId, event.application)
    }
  }

  override fun currentRejections(): List<String> =
    unhealthyVetoRepository.getAll().toList()

  override fun currentRejectionsByApp(application: String): List<String> =
    unhealthyVetoRepository.getAllForApp(application).toList()
}
