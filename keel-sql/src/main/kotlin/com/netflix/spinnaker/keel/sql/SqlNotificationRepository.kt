package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.notifications.NotificationScope
import com.netflix.spinnaker.keel.notifications.NotificationType
import com.netflix.spinnaker.keel.persistence.NotificationRepository
import com.netflix.spinnaker.keel.persistence.metamodel.tables.Notifier.NOTIFIER
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration

class SqlNotificationRepository(
  override val clock: Clock,
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry
) : NotificationRepository(clock) {
  override fun addNotification(scope: NotificationScope, identifier: String, notificationType: NotificationType): Boolean {
    sqlRetry.withRetry(READ) {
      jooq.select(NOTIFIER.NOTIFY_AT)
        .from(NOTIFIER)
        .where(NOTIFIER.SCOPE.eq(scope.name))
        .and(NOTIFIER.IDENTIFIER.eq(identifier))
        .and(NOTIFIER.NOTIFICATION_TYPE.eq(notificationType.name))
        .fetchOne(NOTIFIER.NOTIFY_AT)
    }?.let { notificationTime ->
      // if record exists already, return whether or not to notify
      return notificationTime < clock.millis()
    }

    sqlRetry.withRetry(WRITE) {
      jooq.insertInto(NOTIFIER)
        .set(NOTIFIER.SCOPE, scope.name)
        .set(NOTIFIER.IDENTIFIER, identifier)
        .set(NOTIFIER.NOTIFICATION_TYPE, notificationType.name)
        .set(NOTIFIER.TIME_DETECTED, clock.millis())
        .set(NOTIFIER.NOTIFY_AT, clock.millis())
        .onDuplicateKeyIgnore()
        .execute()
    }
    return true
  }

  override fun clearNotification(scope: NotificationScope, identifier: String, notificationType: NotificationType) {
    sqlRetry.withRetry(WRITE) {
      jooq.deleteFrom(NOTIFIER)
        .where(NOTIFIER.SCOPE.eq(scope.name))
        .and(NOTIFIER.IDENTIFIER.eq(identifier))
        .and(NOTIFIER.NOTIFICATION_TYPE.eq(notificationType.name))
        .execute()
    }
  }

  override fun dueForNotification(scope: NotificationScope, identifier: String, notificationType: NotificationType): Boolean {
    sqlRetry.withRetry(READ) {
      jooq.select(NOTIFIER.NOTIFY_AT)
        .from(NOTIFIER)
        .where(NOTIFIER.SCOPE.eq(scope.name))
        .and(NOTIFIER.IDENTIFIER.eq(identifier))
        .and(NOTIFIER.NOTIFICATION_TYPE.eq(notificationType.name))
        .fetchOne(NOTIFIER.NOTIFY_AT)
    }?.let { notificationTime ->
      return notificationTime < clock.millis()
    }
    // if record doesn't exist, don't notify
    return false
  }

  override fun markSent(scope: NotificationScope, identifier: String, notificationType: NotificationType) {
    val waitingMillis = Duration.parse(waitingDuration).toMillis()
    sqlRetry.withRetry(WRITE) {
      jooq.update(NOTIFIER)
        .set(NOTIFIER.NOTIFY_AT, clock.millis().plus(waitingMillis))
        .where(
          NOTIFIER.SCOPE.eq(scope.name),
          NOTIFIER.IDENTIFIER.eq(identifier),
          NOTIFIER.NOTIFICATION_TYPE.eq(notificationType.name)
        )
        .execute()
    }
  }
}
