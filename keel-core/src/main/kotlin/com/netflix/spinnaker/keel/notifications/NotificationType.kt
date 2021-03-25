package com.netflix.spinnaker.keel.notifications

/**
 * All valid notifiers
 *
 * When you add a new type, make sure you configure what level it should be sent at in
 * [NotificationEventListener.translateFrequencyToEvents]
 */
enum class NotificationType {
  RESOURCE_UNHEALTHY,
  ARTIFACT_PINNED,
  ARTIFACT_UNPINNED,
  ARTIFACT_MARK_AS_BAD,
  ARTIFACT_DEPLOYMENT_FAILED,
  ARTIFACT_DEPLOYMENT_SUCCEEDED,
  APPLICATION_PAUSED,
  APPLICATION_RESUMED,
  LIFECYCLE_EVENT,
  MANUAL_JUDGMENT_AWAIT,
  MANUAL_JUDGMENT_UPDATE,
  MANUAL_JUDGMENT_REJECTED,
  MANUAL_JUDGMENT_APPROVED,
  TEST_PASSED,
  TEST_FAILED,
  DELIVERY_CONFIG_CHANGED
}
