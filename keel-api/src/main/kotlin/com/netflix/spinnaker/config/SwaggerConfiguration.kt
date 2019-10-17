package com.netflix.spinnaker.config

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.classmate.TypeResolver
import com.netflix.spinnaker.keel.api.ApiVersion
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.plugin.ResourceHandler
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
          .forEach {
            log.info("Registering spec type {} with Swagger", it.simpleName)
            additionalModels(resolver.resolveSubtype<ResourceSpec>(it))
          }
      }
      .select()
      .apis(RequestHandlerSelectors.any())
      .paths(PathSelectors.any())
      .build()

  @Bean
  fun apiVersionTypeRuleConvention(resolver: TypeResolver): AlternateTypeRuleConvention =
    object : AlternateTypeRuleConvention {
      override fun rules(): List<AlternateTypeRule> =
        listOf(
          newRule(resolver.resolve<ApiVersion>(), resolver.resolve<String>())
        )

      override fun getOrder(): Int = HIGHEST_PRECEDENCE
    }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

private fun Docket.additionalModels(types: Collection<ResolvedType>): Docket =
  if (types.isEmpty()) {
    this
  } else {
    additionalModels(types.first(), *types.drop(1).toTypedArray())
  }

private inline fun <reified T> TypeResolver.resolve() =
  resolve(T::class.java)

private inline fun <reified P, reified C> TypeResolver.resolveSubtype() =
  resolveSubtype(resolve<P>(), C::class.java)

private inline fun <reified P> TypeResolver.resolveSubtype(subtype: Class<out P>) =
  resolveSubtype(resolve<P>(), subtype)
