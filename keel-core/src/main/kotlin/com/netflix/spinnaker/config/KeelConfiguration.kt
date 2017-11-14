/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.config

import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.jonpeterson.jackson.module.versioning.VersioningModule
import com.netflix.spinnaker.keel.Intent
import com.netflix.spinnaker.keel.IntentActivityRepository
import com.netflix.spinnaker.keel.IntentRepository
import com.netflix.spinnaker.keel.IntentSpec
import com.netflix.spinnaker.keel.attribute.Attribute
import com.netflix.spinnaker.keel.memory.MemoryIntentActivityRepository
import com.netflix.spinnaker.keel.memory.MemoryIntentRepository
import com.netflix.spinnaker.keel.memory.MemoryTraceRepository
import com.netflix.spinnaker.keel.policy.PolicySpec
import com.netflix.spinnaker.keel.tracing.TraceRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.util.ClassUtils
import java.time.Clock

@Configuration
@ComponentScan(basePackages = arrayOf(
  "com.netflix.spinnaker.keel.dryrun",
  "com.netflix.spinnaker.keel.filter"
))
open class KeelConfiguration {

  private val log = LoggerFactory.getLogger(javaClass)

  @Bean
  open fun objectMapper() =
    ObjectMapper().apply {
      registerSubtypes(*findAllSubtypes(Intent::class.java, "com.netflix.spinnaker.keel.intents").toTypedArray())
      registerSubtypes(*findAllSubtypes(IntentSpec::class.java, "com.netflix.spinnaker.keel.intents").toTypedArray())
      registerSubtypes(*findAllSubtypes(PolicySpec::class.java, "com.netflix.spinnaker.keel.policy").toTypedArray())
      registerSubtypes(*findAllSubtypes(Attribute::class.java, "com.netflix.spinnaker.keel.attribute").toTypedArray())
    }
      .registerModule(KotlinModule())
      .registerModule(VersioningModule())
      .registerModule(JavaTimeModule())
      .disable(FAIL_ON_UNKNOWN_PROPERTIES)
      .disable(READ_DATE_TIMESTAMPS_AS_NANOSECONDS)

  private fun findAllSubtypes(clazz: Class<*>, pkg: String): List<Class<*>>
    = ClassPathScanningCandidateComponentProvider(false)
    .apply { addIncludeFilter(AssignableTypeFilter(clazz)) }
    .findCandidateComponents(pkg)
    .map {
      val cls = ClassUtils.resolveClassName(it.beanClassName, ClassUtils.getDefaultClassLoader())
      log.info("Registering ${cls.simpleName}")
      return@map cls
    }

  @Bean
  @ConditionalOnMissingBean(IntentRepository::class)
  open fun memoryIntentRepository(): IntentRepository = MemoryIntentRepository()

  @Bean
  @ConditionalOnMissingBean(IntentActivityRepository::class)
  open fun memoryIntentActivityRepository(): IntentActivityRepository = MemoryIntentActivityRepository()

  @Bean
  @ConditionalOnMissingBean(TraceRepository::class)
  open fun memoryTraceRepository(): TraceRepository = MemoryTraceRepository()

  @Bean open fun clock(): Clock = Clock.systemDefaultZone()
}
