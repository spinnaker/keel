package com.netflix.spinnaker.config

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.classmate.TypeResolver
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.plugin.ResourceHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import springfox.documentation.builders.PathSelectors
import springfox.documentation.builders.RequestHandlerSelectors
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
          .map { it.supportedKind.specClass }
          .map { resolver.resolveSubtype<ResourceSpec>(it) }
          .let { resourceKinds ->
            if (resourceKinds.isNotEmpty()) {
              resourceKinds.forEach {
                log.info("Registering spec type {} with Swagger", it)
              }
              additionalModels(resourceKinds)
            }
          }
      }
      .select()
      .apis(RequestHandlerSelectors.any())
      .paths(PathSelectors.any())
      .build()

  @Bean
  fun swaggerApiProperties() = YamlPropertiesFactoryBean()
    .apply {
      setResources(ClassPathResource("swagger-api.yml"))
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

private inline fun <reified P> TypeResolver.resolveSubtype(subtype: Class<out P>) =
  resolveSubtype(resolve<P>(), subtype)
