package com.netflix.spinnaker.keel.api

interface SCMInfo {

  suspend fun getSCMInfo():
    Map<String, String?>
}
