package com.netflix.spinnaker.keel.bakery

import com.netflix.spinnaker.keel.api.artifacts.BaseLabel
import com.netflix.spinnaker.kork.exceptions.ConfigurationException

class UnknownBaseImage(os: String, label: BaseLabel) :
  ConfigurationException("Could not identify base image for os $os and label $label")
