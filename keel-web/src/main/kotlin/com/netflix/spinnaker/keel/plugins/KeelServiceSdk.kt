package com.netflix.spinnaker.keel.plugins

import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.persistence.KeelRepository

/**
 * A simple SDK that can be consumed by external plugins to access core Keel functionality.
 */
interface KeelServiceSdk {
  val repository: KeelRepository
  val taskLauncher: TaskLauncher
}
