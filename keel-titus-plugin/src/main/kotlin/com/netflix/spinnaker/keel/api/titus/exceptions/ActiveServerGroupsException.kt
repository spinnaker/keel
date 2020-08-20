package com.netflix.spinnaker.keel.api.titus.exceptions

import com.netflix.spinnaker.kork.exceptions.SystemException

class ActiveServerGroupsException(
  val resourceId: String,
  val error: String
) : SystemException("There was an error finding an extra active server group to disable for resource $resourceId: $error")
