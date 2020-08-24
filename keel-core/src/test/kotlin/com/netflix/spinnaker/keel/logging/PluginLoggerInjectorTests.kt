package com.netflix.spinnaker.keel.logging

import com.netflix.spinnaker.config.PluginLoggerInjector
import com.netflix.spinnaker.keel.api.plugins.PluginLogger
import com.netflix.spinnaker.keel.logging.PluginLoggerInjectorTests.TestConfiguration
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEqualTo


@SpringBootTest(
  classes = [PluginLoggerInjector::class, TestConfiguration::class]
)
internal class PluginLoggerInjectorTests : JUnit5Minutests {
  @Autowired
  lateinit var applicationContext: ApplicationContext

  object TestPluginBean {
    @PluginLogger
    lateinit var logger: Logger
  }

  @Configuration
  class TestConfiguration {
    @Bean
    fun pluginBean() = TestPluginBean
  }

  fun tests() = rootContext {
    context("plugin bean with @PlugginLogger annotation") {
      test("has injected logger") {
        val pluginBean = applicationContext.getBean("pluginBean") as TestPluginBean
        expectThat(pluginBean.logger).isA<Logger>()
          .and { get { name }.isEqualTo(TestPluginBean::class.java.name) }
      }
    }
  }
}
