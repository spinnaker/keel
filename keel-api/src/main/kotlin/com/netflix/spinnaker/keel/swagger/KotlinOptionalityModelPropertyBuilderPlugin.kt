package com.netflix.spinnaker.keel.swagger

import com.fasterxml.jackson.annotation.JsonCreator
import org.slf4j.LoggerFactory
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
class KotlinOptionalityModelPropertyBuilderPlugin : ModelPropertyBuilderPlugin {
  @ExperimentalStdlibApi
  override fun apply(context: ModelPropertyContext) {
    context.beanPropertyDefinition.orNull()?.also { property ->
      val kclass = property.primaryMember.declaringClass.kotlin
      val constructor = kclass.constructors.find { it.hasAnnotation<JsonCreator>() } ?: kclass.primaryConstructor
      if (constructor == null) {
        log.error("Can't identify constructor for ${kclass.simpleName} (not a Kotlin class?)")
      } else {
        val constructorParam = constructor.parameters.find { it.name == property.name }
        if (constructorParam == null) {
          log.error("Can't identify constructor param for ${kclass.simpleName}.${property.name}")
        } else {
          val required = !constructorParam.type.isMarkedNullable && !constructorParam.isOptional
          log.info("Marking ${kclass.simpleName}.${constructorParam.name} as ${if (required) "required" else "optional"}")
          context.builder.required(required)
        }
      }
    }
  }

  override fun supports(delimiter: DocumentationType) = true

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
