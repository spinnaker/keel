package com.netflix.spinnaker.config

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.classmate.TypeResolver
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.plugin.ResourceHandler
import com.netflix.spinnaker.keel.swagger.KotlinOptionalityModelPropertyBuilderPlugin
import org.reflections.Reflections
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered.HIGHEST_PRECEDENCE
import springfox.documentation.builders.PathSelectors
import springfox.documentation.builders.RequestHandlerSelectors
import springfox.documentation.schema.AlternateTypeRule
import springfox.documentation.schema.AlternateTypeRuleConvention
import springfox.documentation.schema.AlternateTypeRules.newRule
import springfox.documentation.spi.DocumentationType.SWAGGER_2
import springfox.documentation.spi.schema.ModelPropertyBuilderPlugin
import springfox.documentation.spring.web.plugins.Docket
import springfox.documentation.swagger2.annotations.EnableSwagger2

@Configuration
@EnableSwagger2
class SwaggerConfiguration {
  @Bean
  fun api(resolver: TypeResolver, handlers: List<ResourceHandler<*, *>>): Docket =
    Docket(SWAGGER_2)
      .apply {
        handlers
          .map { it.supportedKind.second }
          .map { resolver.resolveSubtype<ResourceSpec>(it) }
          .let { resourceKinds ->
            if (resourceKinds.isNotEmpty()) {
              resourceKinds.forEach {
                log.info("Registering spec type {} with Swagger", it)
              }
              additionalModels(resourceKinds)
              this.genericModelSubstitutes()
            }
          }
      }
      .select()
      .apis(RequestHandlerSelectors.any())
      .paths(PathSelectors.any())
      .build()

  @Bean
  fun jacksonInferenceConvention(typeResolver: TypeResolver): AlternateTypeRuleConvention =
    object : AlternateTypeRuleConvention {
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
            newRule(typeResolver.resolve(it), typeResolver.resolve<String>())
          }

      override fun getOrder(): Int = HIGHEST_PRECEDENCE
    }

  @Bean
  fun kotlinSwaggerPlugin(resolver: TypeResolver): ModelPropertyBuilderPlugin =
    KotlinOptionalityModelPropertyBuilderPlugin()

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

private inline fun <reified T : Annotation> Class<*>.getAnnotation() =
  getAnnotation(T::class.java)

private inline fun <reified T : Annotation> Reflections.getTypesAnnotatedWith() =
  getTypesAnnotatedWith(T::class.java)

private fun Docket.additionalModels(types: Collection<ResolvedType>): Docket =
  if (types.isEmpty()) {
    this
  } else {
    additionalModels(types.first(), *types.drop(1).toTypedArray())
  }

private inline fun <reified T> TypeResolver.resolve() =
  resolve(T::class.java)

private inline fun <reified P> TypeResolver.resolveSubtype(subtype: Class<out P>) =
  resolveSubtype(resolve<P>(), subtype)
