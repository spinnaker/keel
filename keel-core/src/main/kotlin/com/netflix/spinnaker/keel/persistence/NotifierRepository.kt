package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.notifications.Notifier
import org.springframework.beans.factory.annotation.Value
import java.time.Clock

/**
 * A repository for storing a list of ongoing notifications, and calculating whether
 * they should be resent
 */
abstract class NotifierRepository(
  open val clock: Clock,
  @Value("notify.waiting-duration") var waitingDuration: String = "P1D"
) {

  /**
   * Adds a notification to the list of ongoing notifications.
   * Assumption: each notifier sends only one type of message
   * @return true if we should notify right now
   */
  abstract fun addNotification(resourceId: String, notifier: Notifier): Boolean

  /**
   * Clears a notification from the list of ongoing notifications.
   * Does nothing if notification does not exist.
   */
  abstract fun clearNotification(resourceId: String, notifier: Notifier)

  /**
   * @return true if the notification should be sent
   */
  abstract fun dueForNotification(resourceId: String, notifier: Notifier): Boolean

  /**
   * Marks notification as sent at the current time
   */
  abstract fun markSent(resourceId: String, notifier: Notifier)
}
