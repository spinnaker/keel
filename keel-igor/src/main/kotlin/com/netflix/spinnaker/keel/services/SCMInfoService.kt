package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.igor.ScmService
import com.netflix.spinnaker.keel.api.SCMInfo
import org.springframework.stereotype.Component

@Component
class SCMInfoService(
  private val scmService: ScmService
): SCMInfo {

  override suspend fun getSCMInfo(): Map<String, String?> =
    scmService.getScmInfo()
}
