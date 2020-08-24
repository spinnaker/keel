package com.netflix.spinnaker.config

import com.netflix.spinnaker.keel.api.plugins.PluginLogger
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.stereotype.Component
import org.springframework.util.ReflectionUtils

/**
 * Injects an instance of an SLF4J [Logger] appropriate for the bean class into bean fields
 * annotated with [PluginLogger]. This is to avoid SLF4J binding library conflicts between
 * the hosting service and plugins. Instead of the plugin classes instantiating loggers, the
 * hosting service is responsible for wiring plugin beans with loggers.
 */
@Component
class PluginLoggerInjector : BeanPostProcessor {
  override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any? {
    ReflectionUtils.doWithFields(bean::class.java) { field ->
      // make the field accessible if defined private
      ReflectionUtils.makeAccessible(field)
      if (field.getAnnotation(PluginLogger::class.java) != null) {
        val logger: Logger = LoggerFactory.getLogger(bean::class.java)
        field.set(bean, logger)
      }
    }
    return bean
  }
}
