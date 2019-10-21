package com.netflix.spinnaker.keel.swagger

import com.fasterxml.classmate.TypeResolver
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import org.reflections.Reflections
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import springfox.documentation.schema.AlternateTypeRule
import springfox.documentation.schema.AlternateTypeRuleConvention
import springfox.documentation.schema.AlternateTypeRules

/**
 * Type rule that ensures any classes that use Jackson's [ToStringSerializer] get represented in
 * Swagger documentation as `string`.
 */
@Component
class ToStringSerializerTypeRuleConvention(
  private val typeResolver: TypeResolver
) : AlternateTypeRuleConvention {
  override fun rules(): List<AlternateTypeRule> =
    Reflections("com.netflix.spinnaker.keel.api")
      .getTypesAnnotatedWith<JsonSerialize>()
      .filter {
        it.getAnnotation<JsonSerialize>().using == ToStringSerializer::class
      }
      .also {
        log.info("All these things should be strings in Swagger: {}", it.map(Class<*>::getSimpleName))
      }
      .map {
        AlternateTypeRules.newRule(typeResolver.resolve(it), typeResolver.resolve<String>())
      }

  override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

private inline fun <reified T : Annotation> Reflections.getTypesAnnotatedWith() =
  getTypesAnnotatedWith(T::class.java)

private inline fun <reified T : Annotation> Class<*>.getAnnotation() =
  getAnnotation(T::class.java)

private inline fun <reified T> TypeResolver.resolve() =
  resolve(T::class.java)
