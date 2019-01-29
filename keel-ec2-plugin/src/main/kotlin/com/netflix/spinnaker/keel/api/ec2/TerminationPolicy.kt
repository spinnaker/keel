package com.netflix.spinnaker.keel.api.ec2

enum class TerminationPolicy {
  OldestInstance, NewestInstance, OldestLaunchConfiguration, ClosestToNextInstanceHour
}
