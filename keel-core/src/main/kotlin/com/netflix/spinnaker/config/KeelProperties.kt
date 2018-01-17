package com.netflix.spinnaker.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("keel")
class KeelProperties {
  var prettyPrintJson: Boolean = false
  var immediatelyRunIntents: Boolean = true
  var maxConvergenceLogEntriesPerIntent: Int = 720 // one entry every 30 s, this will keep 6 hours of logs
}
