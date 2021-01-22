package com.netflix.spinnaker.keel.notifications

/**
 * All valid notifiers
 */
enum class NotificationType {
  UNHEALTHY_RESOURCE,
  PINNED_ARTIFACT,
  UNPINNED_ARTIFACT,
  MARK_AS_BAD_ARTIFACT,
  PAUSED_APPLICATION,
  RESUMED_APPLICATION,
  LIFECYCLE_EVENT
}
