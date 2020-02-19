package com.netflix.spinnaker.keel.apidocs

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.module.kotlin.isKotlinClass
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.media.Schema
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.findParameterByName
import kotlin.reflect.full.primaryConstructor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Attempts to determine which properties of an `object` are `required` based on the [JsonCreator]
 * or default constructor in the associated Kotlin class. Any parameter that is non-nullable and has
 * no default value is considered required.
 *
 * @see kotlin.reflect.KParameter.isOptional
 * @see kotlin.reflect.KType.isMarkedNullable
 */
@Component
class KotlinOptionalPropertyConverter : BaseModelConverter() {

  override fun resolve(
    annotatedType: AnnotatedType,
    context: ModelConverterContext,
    chain: MutableIterator<ModelConverter>
  ): Schema<*>? =
    super.resolve(annotatedType, context, chain)
      ?.also { schema ->
        if (annotatedType.baseType.isKotlinClass()) {
          if (annotatedType.isResolveAsRef) {
            val referencedSchema = context.definedModels[annotatedType.baseType.simpleName]
            if (referencedSchema == null) {
              log.warn("Referenced schema ${annotatedType.baseType.simpleName} not found")
            } else {
              applyRequired(annotatedType.baseType.kotlin, referencedSchema)
            }
          } else {
            applyRequired(annotatedType.baseType.kotlin, schema)
          }
        }
      }

  private fun applyRequired(kotlinClass: KClass<*>, schema: Schema<*>) {
    if (schema.properties == null) return

    val constructor = kotlinClass.jsonCreator ?: kotlinClass.primaryConstructor
    if (constructor == null) {
      log.warn("No JsonCreator or constructor found for ${kotlinClass.qualifiedName}")
    } else {
      schema.properties.forEach { (name, propertySchema) ->
        val param = constructor.findParameterByName(name)
        if (param == null) {
          log.warn("No parameter $name found on JsonCreator or constructor for ${kotlinClass.qualifiedName}")
        } else {
          if (!param.isOptional && !param.type.isMarkedNullable) {
            if (schema.required == null || !schema.required.contains(name)) {
              schema.addRequiredItem(name)
            }
          }
          propertySchema.nullable = param.type.isMarkedNullable
        }
      }
    }
  }

  private val <T : Any> KClass<T>.jsonCreator: KFunction<T>?
    get() = constructors.find { it.findAnnotation<JsonCreator>() != null }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
