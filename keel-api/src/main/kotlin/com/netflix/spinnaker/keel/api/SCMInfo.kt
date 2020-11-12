package com.netflix.spinnaker.keel.api

interface SCMInfo{

  suspend fun getScmInfo():
    Map<String, String?>
}
