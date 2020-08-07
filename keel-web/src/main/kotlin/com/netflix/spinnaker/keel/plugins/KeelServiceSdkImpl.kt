package com.netflix.spinnaker.keel.plugins

import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.persistence.KeelRepository

class KeelServiceSdkImpl(
  override val repository: KeelRepository,
  override val taskLauncher: TaskLauncher
) : KeelServiceSdk
