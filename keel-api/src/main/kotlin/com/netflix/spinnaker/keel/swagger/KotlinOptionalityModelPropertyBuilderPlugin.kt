package com.netflix.spinnaker.keel.swagger

import com.fasterxml.jackson.annotation.JsonCreator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import springfox.documentation.spi.DocumentationType
import springfox.documentation.spi.schema.ModelPropertyBuilderPlugin
import springfox.documentation.spi.schema.contexts.ModelPropertyContext
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * Springfox / Swagger plugin that tries to infer `required` status for a model property based on
 * Kotlin nullability and/or presence of a default value in the constructor parameter corresponding
 * to the property.
 */
@Component
class KotlinOptionalityModelPropertyBuilderPlugin : ModelPropertyBuilderPlugin {
  @ExperimentalStdlibApi
  override fun apply(context: ModelPropertyContext) {
    context.beanPropertyDefinition.orNull()?.also { property ->
      val kclass = property.primaryMember.declaringClass.kotlin
      // use a construtor with @JsonCreator by preference, otherwise the primary (i.e. what Jackson will use)
      val constructor = kclass.constructors.find { it.hasAnnotation<JsonCreator>() } ?: kclass.primaryConstructor
      if (constructor == null) {
        log.warn("Can't identify constructor for ${kclass.simpleName} (not a Kotlin class?)")
      } else {
        val constructorParam = constructor.parameters.find { it.name == property.name }
        if (constructorParam == null) {
          log.warn("Can't identify constructor param for ${kclass.simpleName}.${property.name}")
        } else {
          val required = !constructorParam.type.isMarkedNullable && !constructorParam.isOptional
          log.debug("Marking ${kclass.simpleName}.${constructorParam.name} as ${if (required) "required" else "optional"}")
          context.builder.required(required)
        }
      }
    }
  }

  override fun supports(delimiter: DocumentationType) = true

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
