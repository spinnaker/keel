package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.notifications.NotificationScope
import com.netflix.spinnaker.keel.notifications.Notifier
import com.netflix.spinnaker.keel.persistence.NotifierRepository
import com.netflix.spinnaker.keel.persistence.metamodel.tables.Notifier.NOTIFIER
import com.netflix.spinnaker.keel.sql.RetryCategory.READ
import com.netflix.spinnaker.keel.sql.RetryCategory.WRITE
import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration

class SqlNotifierRepository(
  override val clock: Clock,
  private val jooq: DSLContext,
  private val sqlRetry: SqlRetry
) : NotifierRepository(clock) {
  override fun addNotification(scope: NotificationScope, identifier: String, notifier: Notifier): Boolean {
    sqlRetry.withRetry(READ) {
      jooq.select(NOTIFIER.NOTIFY_AT)
        .from(NOTIFIER)
        .where(NOTIFIER.SCOPE.eq(scope.name))
        .and(NOTIFIER.IDENTIFIER.eq(identifier))
        .and(NOTIFIER.NOTIFIER_NAME.eq(notifier.name))
        .fetchOne(NOTIFIER.NOTIFY_AT)
    }?.let { notificationTime ->
      // if record exists already, return whether or not to notify
      return notificationTime < clock.millis()
    }

    sqlRetry.withRetry(WRITE) {
      jooq.insertInto(NOTIFIER)
        .set(NOTIFIER.SCOPE, scope.name)
        .set(NOTIFIER.IDENTIFIER, identifier)
        .set(NOTIFIER.NOTIFIER_NAME, notifier.name)
        .set(NOTIFIER.TIME_DETECTED, clock.millis())
        .set(NOTIFIER.NOTIFY_AT, clock.millis())
        .onDuplicateKeyIgnore()
        .execute()
    }
    return true
  }

  override fun clearNotification(scope: NotificationScope, identifier: String, notifier: Notifier) {
    sqlRetry.withRetry(WRITE) {
      jooq.deleteFrom(NOTIFIER)
        .where(NOTIFIER.SCOPE.eq(scope.name))
        .and(NOTIFIER.IDENTIFIER.eq(identifier))
        .and(NOTIFIER.NOTIFIER_NAME.eq(notifier.name))
        .execute()
    }
  }

  override fun dueForNotification(scope: NotificationScope, identifier: String, notifier: Notifier): Boolean {
    sqlRetry.withRetry(READ) {
      jooq.select(NOTIFIER.NOTIFY_AT)
        .from(NOTIFIER)
        .where(NOTIFIER.SCOPE.eq(scope.name))
        .and(NOTIFIER.IDENTIFIER.eq(identifier))
        .and(NOTIFIER.NOTIFIER_NAME.eq(notifier.name))
        .fetchOne(NOTIFIER.NOTIFY_AT)
    }?.let { notificationTime ->
      return notificationTime < clock.millis()
    }
    // if record doesn't exist, don't notify
    return false
  }

  override fun markSent(scope: NotificationScope, identifier: String, notifier: Notifier) {
    val waitingMillis = Duration.parse(waitingDuration).toMillis()
    sqlRetry.withRetry(WRITE) {
      jooq.update(NOTIFIER)
        .set(NOTIFIER.NOTIFY_AT, clock.millis().plus(waitingMillis))
        .where(
          NOTIFIER.SCOPE.eq(scope.name),
          NOTIFIER.IDENTIFIER.eq(identifier),
          NOTIFIER.NOTIFIER_NAME.eq(notifier.name)
        )
        .execute()
    }
  }
}
