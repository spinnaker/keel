package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.plugin.ResourceHandler
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.reactor.flux
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

@RestController
@RequestMapping(path = ["/kinds"])
class KindController(val plugins: List<ResourceHandler<*>>) {

  @ExperimentalCoroutinesApi
  @GetMapping(produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE])
  fun get(): Flux<Map<String, Any>> =
    scope.flux {
      plugins
        .groupBy { it.apiVersion }
        .map { (apiVersion, plugin) ->
          mapOf(
            "api-version" to apiVersion,
            "kinds" to plugin.map { it.supportedKind.first.singular }
          )
        }
        .forEach {
          send(it)
        }
      close()
    }

  private val scope: CoroutineScope
    get() = CoroutineScope(IO)
}
