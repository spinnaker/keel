package com.netflix.spinnaker.keel.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.ec2.EC2_SECURITY_GROUP_V1
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupSpec
import com.netflix.spinnaker.keel.api.plugins.SupportedKind
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import com.netflix.spinnaker.keel.api.support.register
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.SubmittedResource
import com.netflix.spinnaker.keel.ec2.jackson.registerEc2Subtypes
import com.netflix.spinnaker.keel.ec2.jackson.registerKeelEc2ApiModule
import com.netflix.spinnaker.keel.extensions.DefaultExtensionRegistry
import com.netflix.spinnaker.keel.jackson.registerKeelApiModule
import com.netflix.spinnaker.keel.rest.DeliveryConfigYamlParsingFilterTests.Ec2JsonTestConfiguration
import com.netflix.spinnaker.keel.serialization.configuredYamlMapper
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jackson.JsonComponentModule
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.http.HttpServletRequestWrapper

@SpringBootTest(
  classes = [Ec2JsonTestConfiguration::class],
  webEnvironment = NONE
)
class DeliveryConfigYamlParsingFilterTests : JUnit5Minutests {
  @Configuration
  @ComponentScan(basePackages = ["com.netflix.spinnaker.keel.ec2.jackson"])
  // TODO [LFP 1/20/2021]: de-duplicate with the same class in keel-ec2-plugin.
  //  I've tried extracting this to a separate test support module, but it depends on keel-ec2-plugin
  //  and ends up auto-wiring undesired Spring beans/config from that module.
  internal class Ec2JsonTestConfiguration {
    @Bean
    fun jsonComponentModule() = JsonComponentModule()

    @Bean
    @Primary
    fun mapper(jsonComponentModule: JsonComponentModule): ObjectMapper =
      configuredYamlMapper()
        .registerModule(jsonComponentModule)
        .registerKeelApiModule()
        .registerKeelEc2ApiModule()

    @Bean
    fun registry(mappers: List<ObjectMapper>): ExtensionRegistry = DefaultExtensionRegistry(mappers)
        .also {
          it.registerEc2Subtypes()
          it.register(
            EC2_SECURITY_GROUP_V1.specClass,
            EC2_SECURITY_GROUP_V1.kind.toString()
          )
        }
  }

  @Autowired
  lateinit var objectMapper: ObjectMapper

  object Fixture {
    val chain: FilterChain = mockk()
    val filter = DeliveryConfigYamlParsingFilter()
    val request = MockHttpServletRequest("POST", "/delivery-configs")
    val response = MockHttpServletResponse()

    init {
      request.contentType = "application/x-yaml"
      request.setContent(
        """
        application: nimakspin
        serviceAccount: myservice@keel.io
        artifacts: []
        hasManagedResources: true
        environments:
          - name: testing
            hasManagedResources: true
            resources:
            -
              kind: ec2/security-group@v1
              spec: &secgrp
                moniker:
                  app: nimakspin
                locations:
                  account: my-aws-account
                  vpc: my-vpc
                  regions:
                  - name: us-west-2
                description: "test LoadBalancer securityGroup by ALB Ingress Controller"
                inboundRules:
                - protocol: TCP
                  portRange:
                    startPort: 80
                    endPort: 80
                  blockRange: "0.0.0.0/0"
                - protocol: TCP
                  portRange:
                    startPort: 22
                    endPort: 22
                  blockRange: "0.0.0.0/0"
          - name: prod
            hasManagedResources: true
            resources:
            - kind: ec2/security-group@v1
              spec:
                << : *secgrp
                moniker:
                  app: nimakspin
                  stack: prod
        """.trimIndent().toByteArray()
      )
    }

    private fun SupportedKind<*>.toNamedType(): NamedType = NamedType(specClass, kind.toString())
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture
    }

    context("DeliveryConfigYamlParsingfilter") {
      before {
        every {
          chain.doFilter(any(), any())
        } just Runs
      }

      test("correctly parses a delivery config with anchors and aliases") {
        filter.doFilter(request, response, chain)

        val normalizedRequest = slot<ServletRequest>()
        verify {
          chain.doFilter(capture(normalizedRequest), response)
        }

        expectThat(normalizedRequest.captured)
          .isA<HttpServletRequestWrapper>()
          .get { contentType }.isEqualTo("application/json")

        val deliveryConfig: SubmittedDeliveryConfig = objectMapper.readValue(normalizedRequest.captured.inputStream)
        expectThat(deliveryConfig) {
          get { environments }.hasSize(2)
          get { environments.find { it.name == "prod" }!!.resources }.hasSize(1)
          get { environments.find { it.name == "prod" }!!.resources.first() }
            .isA<SubmittedResource<SecurityGroupSpec>>()
            .and {
              get { spec.moniker.stack }.isEqualTo("prod")
              get { spec.inboundRules }.hasSize(2)
            }
        }
      }
    }
  }
}
