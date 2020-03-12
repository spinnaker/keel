package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.ResourceKind.Companion.parseKind
import com.netflix.spinnaker.keel.api.plugins.ResourceHandler
import com.netflix.spinnaker.keel.api.plugins.supporting
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.core.api.SubmittedResource
import com.netflix.spinnaker.keel.core.parseMoniker
import com.netflix.spinnaker.keel.logging.TracingSupport.Companion.withTracingContext
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import kotlinx.coroutines.runBlocking
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.util.comparator.NullSafeComparator
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/export"])
class ExportController(
  private val handlers: List<ResourceHandler<*, *>>,
  private val cloudDriverCache: CloudDriverCache
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  /**
   * Assist for mapping between Deck and Clouddriver cloudProvider names
   * and Keel's plugin namespace.
   */
  private val cloudProviderOverrides = mapOf(
    "aws" to "ec2"
  )

  private val typeToKind = mapOf(
    "classicloadbalancer" to "classic-load-balancer",
    "classicloadbalancers" to "classic-load-balancer",
    "applicationloadbalancer" to "application-load-balancer",
    "applicationloadbalancers" to "application-load-balancer",
    "securitygroup" to "security-group",
    "securitygroups" to "security-group",
    "cluster" to "cluster",
    "clustersx" to "cluster"
  )

  /**
   * This route is location-less; given a resource name that can be monikered,
   * type, and account, all locations configured for the account are scanned for
   * matching resources that can be combined into a multi-region spec.
   *
   * Types are derived from Clouddriver's naming convention. It is assumed that
   * converting these to kebab case (i.e. securityGroups -> security-groups) will
   * match either the singular or plural of a [ResourceHandler]'s
   * [ResourceHandler.supportedKind].
   */
  @GetMapping(
    path = ["/{cloudProvider}/{account}/{type}/{name}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun get(
    @PathVariable("cloudProvider") cloudProvider: String,
    @PathVariable("account") account: String,
    @PathVariable("type") type: String,
    @PathVariable("name") name: String,
    @RequestHeader("X-SPINNAKER-USER") user: String
  ): SubmittedResource<*> {
    val group = cloudProviderOverrides[cloudProvider] ?: cloudProvider

    val kind = type.toLowerCase().let { t ->
      // Detect whether the type provided is fully-qualified
      if (ResourceKind.RESOURCE_KIND_FORMAT.matches(t)) {
        t
      } else {
        // If not, look up the latest supported version based on the group and kind, or use the default if none found
        val normalizedType = typeToKind.getOrDefault(t, t)
        val latestVersion = handlers
          .supporting(group, normalizedType)
          .map { h -> h.supportedKind.kind.version }
          .sortedWith(versionComparator)
          .last()
        "$group/$normalizedType@v$latestVersion"
      }
    }.let(::parseKind)

    val handler = handlers.supporting(kind)
    val exportable = Exportable(
      cloudProvider = group,
      account = account,
      user = user,
      moniker = parseMoniker(name),
      regions = (
        cloudDriverCache
          .credentialBy(account)
          .attributes["regions"] as List<Map<String, Any>>
        )
        .map { it["name"] as String }
        .toSet(),
      kind = kind
    )

    return runBlocking {
      withTracingContext(exportable) {
        log.info("Exporting resource ${exportable.toResourceId()}")
        SubmittedResource(
          kind = kind,
          spec = handler.export(exportable)
        )
      }
    }
  }

  companion object {
    private val versionPrefix = """^v""".toRegex()
    val versionComparator: Comparator<String> = NullSafeComparator<String>(
      Comparator<String> { s1, s2 ->
        DefaultArtifactVersion(s1?.replace(versionPrefix, "")).compareTo(
          DefaultArtifactVersion(s2?.replace(versionPrefix, ""))
        )
      },
      true // null is considered lower
    )
  }
}
