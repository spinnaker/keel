package com.netflix.spinnaker.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter

/**
 * Disables several security-related filters for Slack callback requests, which are application/x-www-form-urlencoded
 * and cause the filters to read the request body while reading parameters, which in turn causes the input stream
 * to be closed before the request gets to the callback controller, appearing as an empty request body.
 */
@Configuration
@Order(1)
@EnableWebSecurity
class SlackRequestSecurityConfiguration : WebSecurityConfigurerAdapter() {

  override fun configure(http: HttpSecurity) {
    http
      .antMatcher("/slack/notifications/callbacks")
      .authorizeRequests()
      .anyRequest()
      .permitAll()
      .and()
      .csrf()
      .ignoringAntMatchers("/slack/notifications/callbacks")
  }
}
