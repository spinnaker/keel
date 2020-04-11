package com.netflix.spinnaker.keel.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.api.plugins.ResourceHandler
import com.netflix.spinnaker.keel.api.plugins.supporting
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.SubmittedEnvironment
import com.netflix.spinnaker.keel.core.api.SubmittedResource
import com.netflix.spinnaker.keel.core.parseMoniker
import com.netflix.spinnaker.keel.front50.Front50Service
import com.netflix.spinnaker.keel.front50.model.BakeStage
import com.netflix.spinnaker.keel.front50.model.Pipeline
import com.netflix.spinnaker.keel.logging.TracingSupport.Companion.withTracingContext
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import java.lang.IllegalArgumentException
import kotlinx.coroutines.runBlocking
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.security.access.prepost.PreAuthorize
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
  private val cloudDriverCache: CloudDriverCache,
  private val front50Service: Front50Service,
  private val jsonMapper: ObjectMapper
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  companion object {
    val versionSuffix = """@v(\d+)$""".toRegex()
    private val versionPrefix = """^v""".toRegex()
    val versionComparator: Comparator<String> = NullSafeComparator<String>(
      Comparator<String> { s1, s2 ->
        DefaultArtifactVersion(s1?.replace(versionPrefix, "")).compareTo(
          DefaultArtifactVersion(s2?.replace(versionPrefix, ""))
        )
      },
      true // null is considered lower
    )

    val EXPORTABLE_PIPELINE_PATTERNS = listOf(
      listOf("bake", "deploy", "manualJudgment", "deploy", "manualJudgment", "deploy"),
      listOf("findImage", "deploy", "manualJudgment", "deploy", "manualJudgment", "deploy"),
      listOf("deploy", "manualJudgment", "deploy", "manualJudgment", "deploy"),
      listOf("deploy", "deploy", "deploy"),
      listOf("findImage", "deploy"),
      listOf("findImageFromTags", "deploy"),
      listOf("bake", "deploy")
    )
  }

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
    val kind = parseKind(cloudProvider, type)
    val handler = handlers.supporting(kind)
    val exportable = Exportable(
      cloudProvider = kind.group,
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

  @GetMapping(
    path = ["/pipelines/{application}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  @PreAuthorize("@authorizationSupport.hasApplicationPermission('READ', 'APPLICATION', #application)")
  fun get(
    @PathVariable("application") application: String,
    @RequestHeader("X-SPINNAKER-USER") user: String
  ): SubmittedDeliveryConfig {

    val pipelines = runBlocking {
      front50Service.pipelinesByApplication(application, user)
    }
      .filter { pipeline ->
        val shape = pipeline.stages.map { stage -> stage.type }

        !pipeline.disabled &&
          !pipeline.fromTemplate &&
          !pipeline.hasParallelStages &&
          shape in EXPORTABLE_PIPELINE_PATTERNS
      }

    val pipelinesToBakesToArtifacts: Map<Pipeline, Pair<BakeStage, DebianArtifact>> = pipelines
      .filter { pipeline -> pipeline.stages.any { stage -> stage is BakeStage } }
      .associateWith { pipeline ->
        val bake = pipeline.stages.filterIsInstance<BakeStage>().first()
        with(bake) {
          bake to DebianArtifact(
            name = `package`,
            vmOptions = VirtualMachineOptions(
              baseLabel = baseLabel,
              baseOs = baseOs,
              regions = regions,
              storeType = storeType
            ),
            statuses = try {
              setOf(ArtifactStatus.valueOf(baseLabel.name))
            } catch (e: IllegalArgumentException) {
              emptySet<ArtifactStatus>()
            }
          )
        }
      }

    val environments = pipelinesToBakesToArtifacts.mapNotNull { (pipeline, bakeToArtifact) ->
      val (bake, artifact) = bakeToArtifact
      val deploy = pipeline.findDownstreamDeploy(bake)

      if (deploy == null || deploy.clusters.isEmpty()) {
        log.warn("Downstream deploy stage not found after bake stage ${bake.name} for pipeline ${pipeline.name}")
        null
      } else {
        if (deploy.clusters.size > 1) {
          log.warn("Deploy stage ${deploy.name} for pipeline ${pipeline.name} has more than 1 cluster. Exporting only the first.")
        }

        val kind = ResourceKind.parseKind("ec2/cluster@v1")
        val handler = handlers.supporting(kind)
        val spec = handler.export(deploy, artifact)

        if (spec == null) {
          log.warn("No resource exported for kind $kind")
          null
        } else {
          SubmittedEnvironment(
            name = deploy.clusters.first().stack,
            resources = setOf(
              SubmittedResource(
                kind = kind,
                metadata = emptyMap(), // TODO
                spec = spec
              )
            )
          )
        }
      }
    }.toSet()

    val artifacts = pipelinesToBakesToArtifacts.map { (_, bakeToArtifact) ->
      bakeToArtifact.second
    }.distinct().toSet()

    val deliveryConfig = SubmittedDeliveryConfig(
      name = application,
      application = application,
      serviceAccount = user,
      artifacts = artifacts,
      environments = environments
    )

    return deliveryConfig
  }

  fun parseKind(cloudProvider: String, type: String) =
    type.toLowerCase().let { t1 ->
      val group = cloudProviderOverrides[cloudProvider] ?: cloudProvider
      var version: String? = null
      val normalizedType = if (versionSuffix.containsMatchIn(t1)) {
        version = versionSuffix.find(t1)!!.groups[1]?.value
        t1.replace(versionSuffix, "")
      } else {
        t1
      }.let { t2 ->
        typeToKind.getOrDefault(t2, t2)
      }

      if (version == null) {
        version = handlers
          .supporting(group, normalizedType)
          .map { h -> h.supportedKind.kind.version }
          .sortedWith(versionComparator)
          .last()
      }

      (version != null) || error("Unable to find version for group $group, $normalizedType")

      "$group/$normalizedType@v$version"
    }.let(ResourceKind.Companion::parseKind)
}
