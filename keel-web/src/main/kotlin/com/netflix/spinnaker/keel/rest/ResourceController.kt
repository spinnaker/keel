/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.actuation.ResourcePersister
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.api.SubmittedResource
import com.netflix.spinnaker.keel.diff.AdHocDiffer
import com.netflix.spinnaker.keel.diff.DiffResult
import com.netflix.spinnaker.keel.exceptions.FailedNormalizationException
import com.netflix.spinnaker.keel.pause.ResourcePauser
import com.netflix.spinnaker.keel.persistence.NoSuchResourceException
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.ResourceStatus
import com.netflix.spinnaker.keel.persistence.ResourceStatus.PAUSED
import com.netflix.spinnaker.keel.plugin.UnsupportedKind
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/resources"])
class ResourceController(
  private val resourceRepository: ResourceRepository,
  private val resourcePersister: ResourcePersister,
  private val resourcePauser: ResourcePauser,
  private val adHocDiffer: AdHocDiffer
) {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  @GetMapping(
    path = ["/{id}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun get(@PathVariable("id") id: ResourceId): Resource<*> {
    log.debug("Getting: $id")
    return resourceRepository.get(id)
  }

  @GetMapping(
    path = ["/{id}/status"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun getStatus(@PathVariable("id") id: ResourceId): ResourceStatus =
    if (resourcePauser.isPaused(id)) { // todo eb: we could make determining status easier and more straight forward.
      PAUSED
    } else {
      resourceRepository.getStatus(id)
    }

  @PostMapping(
    path = ["/{id}/pause"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun pauseResource(@PathVariable("id") id: ResourceId) {
    resourcePauser.pauseResource(id)
  }

  @DeleteMapping(
    path = ["/{id}/pause"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun resumeResource(@PathVariable("id") id: ResourceId) {
    resourcePauser.resumeResource(id)
  }

  @PostMapping(
    path = ["/diff"],
    consumes = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  @PreAuthorize("@authorizationSupport.userCanModifySpec(#user, #resource.spec)")
  fun diff(@RequestHeader("X-SPINNAKER-USER") user: String, @RequestBody resource: SubmittedResource<*>): DiffResult {
    log.debug("Diffing: $resource")
    return runBlocking { adHocDiffer.calculate(resource) }
  }

  @ExceptionHandler(NoSuchResourceException::class)
  @ResponseStatus(NOT_FOUND)
  fun onNotFound(e: NoSuchResourceException) {
    log.error(e.message)
  }

  @ExceptionHandler(HttpMessageConversionException::class)
  @ResponseStatus(BAD_REQUEST)
  fun onParseFailure(e: HttpMessageConversionException): Map<String, Any?> {
    log.error(e.message)
    return mapOf("message" to (e.cause?.message ?: e.message))
  }

  @ExceptionHandler(FailedNormalizationException::class)
  @ResponseStatus(UNPROCESSABLE_ENTITY)
  fun onParseFailure(e: FailedNormalizationException): Map<String, Any?> {
    log.error(e.message)
    return mapOf("message" to (e.cause?.message ?: e.message))
  }

  @ExceptionHandler(UnsupportedKind::class)
  @ResponseStatus(UNPROCESSABLE_ENTITY)
  fun onUnsupportedKind(e: UnsupportedKind): Map<String, Any?> {
    log.error(e.message)
    return mapOf("message" to e.message)
  }
}
