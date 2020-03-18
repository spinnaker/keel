package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import com.netflix.spinnaker.kork.exceptions.SystemException
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/test"])
class TestController {

  @GetMapping(
    path = ["/error"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun throwAnException(): String {
    throw SystemException("GET request was made against test endpoint that is configured to always throw an exception")
  }
}
