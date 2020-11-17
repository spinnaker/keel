package com.netflix.spinnaker.keel.lifecycle

import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.FAILED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.SUCCEEDED

enum class LifecycleEventStatus {
  NOT_STARTED, RUNNING, SUCCEEDED, FAILED, UNKNOWN;
}

fun LifecycleEventStatus.isEndingStatus(): Boolean =
  this == SUCCEEDED || this == FAILED
