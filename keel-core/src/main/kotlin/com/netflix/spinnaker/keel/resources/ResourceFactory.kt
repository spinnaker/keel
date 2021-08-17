package com.netflix.spinnaker.keel.resources

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceKind
import de.huxhorn.sulky.ulid.ULID
import org.springframework.stereotype.Component

/**
 * Provides logic to construct resources and auto-migrate their specs to the latest version.
 */
@Component
class ResourceFactory(
  private val objectMapper: ObjectMapper,
  private val resourceSpecIdentifier: ResourceSpecIdentifier,
  private val specMigrators: List<SpecMigrator<*, *>>
) {
  /**
   * @return a [Resource] of the specified [kind] with the given metadata and spec, potentially modified
   * by running any applicable spec migrations (i.e. the resource may have a different spec version than
   * the one defined in the spec JSON).
   */
  fun create(kind: String, metadataJson: String, specJson: String) =
    createRaw(kind, metadataJson, specJson).let { resource ->
      specMigrators
        .migrate(resource.kind, resource.spec)
        .let { (endKind, endSpec) ->
          Resource(endKind, resource.metadata, endSpec)
        }
    }

  /**
   * @return a [Resource] of the specified [kind] with the given metadata and spec, without running
   * any spec migrations (i.e. the resource will have the exact spec version as defined in the spec).
   */
  fun createRaw(kind: String, metadataJson: String, specJson: String) =
    ResourceKind.parseKind(kind).let {
      Resource(
        it,
        objectMapper.readValue<Map<String, Any?>>(metadataJson).asResourceMetadata(),
        objectMapper.readValue(specJson, resourceSpecIdentifier.identify(it))
      )
    }

  private fun Map<String, Any?>.asResourceMetadata(): Map<String, Any?> =
    mapValues { if (it.key == "uid") ULID.parseULID(it.value.toString()) else it.value }
}
