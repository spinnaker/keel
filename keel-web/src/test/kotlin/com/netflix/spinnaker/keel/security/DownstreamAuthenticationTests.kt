package com.netflix.spinnaker.keel.security

import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext

class DownstreamAuthenticationTests : JUnit5Minutests {

  fun tests() = rootContext {
//    missing authentication headers: clouddriver, orca, mahe
  }
}
