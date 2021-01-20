package com.netflix.spinnaker.keel.api.ec2

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import com.netflix.spinnaker.keel.api.support.register
import com.netflix.spinnaker.keel.ec2.jackson.registerEc2Subtypes
import com.netflix.spinnaker.keel.ec2.jackson.registerKeelEc2ApiModule
import com.netflix.spinnaker.keel.jackson.registerKeelApiModule
import org.springframework.boot.jackson.JsonComponentModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Configures the primary object mapper with auto-wired custom JSON serializers and deserializers
 * via [JsonComponentModule].
 */
@Configuration
@ComponentScan(basePackages = ["com.netflix.spinnaker.keel.ec2.jackson"])
class Ec2JsonTestConfiguration {
  @Bean
  fun jsonComponentModule() = JsonComponentModule()

  @Bean
  @Primary
  fun mapper(jsonComponentModule: JsonComponentModule): ObjectMapper =
    com.netflix.spinnaker.keel.serialization.configuredYamlMapper()
      .registerModule(jsonComponentModule)
      .registerKeelApiModule()
      .registerKeelEc2ApiModule()

  @Bean
  fun registry(mappers: List<ObjectMapper>): ExtensionRegistry =
    com.netflix.spinnaker.keel.extensions.DefaultExtensionRegistry(
      mappers
    )
      .also {
        it.registerEc2Subtypes()
        it.register(
          EC2_SECURITY_GROUP_V1.specClass,
          EC2_SECURITY_GROUP_V1.kind.toString()
        )
      }
}