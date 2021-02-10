package com.netflix.spinnaker.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConditionalOnProperty("slack.enabled")
@ConfigurationProperties(prefix = "slack")
class SlackConfiguration {
  var token: String? = null
}
