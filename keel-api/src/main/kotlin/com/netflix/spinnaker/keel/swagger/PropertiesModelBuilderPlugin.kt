package com.netflix.spinnaker.keel.swagger

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.classmate.TypeResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import springfox.documentation.builders.ModelBuilder
import springfox.documentation.builders.ModelPropertyBuilder
import springfox.documentation.spi.DocumentationType
import springfox.documentation.spi.schema.ModelBuilderPlugin
import springfox.documentation.spi.schema.ModelPropertyBuilderPlugin
import springfox.documentation.spi.schema.contexts.ModelContext
import springfox.documentation.spi.schema.contexts.ModelPropertyContext
import java.util.Properties

/**
 * Loads Swagger documentation values from YAML.
 */
@Component
class PropertiesModelBuilderPlugin(
  private val swaggerApiProperties: Properties,
  private val resolver: TypeResolver
) : ModelBuilderPlugin, ModelPropertyBuilderPlugin {
  override fun apply(context: ModelContext) {
    val type = context.resolvedType(resolver)
    val key = "swagger.api.models.${type.simpleName}"
    with(context) {
      ifPropertyPresent(key, "description") { description(it) }
      ifPropertyPresent(key, "example") { example(it as Any) }
    }
  }

  override fun apply(context: ModelPropertyContext) {
    context.beanPropertyDefinition.orNull()?.also { property ->
      val type = property.primaryMember.declaringClass
      val key = "swagger.api.models.${type.simpleName}.properties.${property.name}"
      with(context) {
        ifPropertyPresent(key, "description") { description(it) }
        ifPropertyPresent(key, "hidden") { isHidden(it.toBoolean()) }
        ifPropertyPresent(key, "example") { example(it as Any) }
        ifPropertyPresent(key, "default") { defaultValue(it) }
        ifPropertyPresent(key, "dataType") { type(context.resolver.resolve(javaClass.classLoader.loadClass(it))) }
      }
    }
  }

  private fun ModelContext.ifPropertyPresent(baseKey: String, subKey: String, callback: ModelBuilder.(String) -> Unit) {
    swaggerApiProperties.getProperty("$baseKey.$subKey")?.also { builder.callback(it) }
  }

  private fun ModelPropertyContext.ifPropertyPresent(baseKey: String, subKey: String, callback: ModelPropertyBuilder.(String) -> Unit) {
    swaggerApiProperties.getProperty("$baseKey.$subKey")?.also { builder.callback(it) }
  }

  override fun supports(delimiter: DocumentationType) = true

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

private val ResolvedType.simpleName: String
  get() = typeName.substringAfterLast(".")
