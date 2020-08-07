package com.netflix.spinnaker.keel.resources

import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.plugins.SupportedKind
import com.netflix.spinnaker.keel.api.plugins.UnsupportedKind
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import com.netflix.spinnaker.keel.api.support.extensionsOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Used to identify the [ResourceSpec] implementation that belongs with a [ResourceKind] when doing
 * things like reading resources from the database, or parsing JSON.
 */
@Component
class ResourceSpecIdentifier @Autowired constructor (
  private val extensionRegistry: ExtensionRegistry?
) {
  private var _kinds: List<SupportedKind<*>>? = null

  private val kinds: List<SupportedKind<*>>
    get() {
      return when {
        _kinds != null -> _kinds!!
        extensionRegistry != null -> extensionRegistry
          .extensionsOf<ResourceSpec>()
          .entries
          .map { SupportedKind(ResourceKind.parseKind(it.key), it.value) }
        else -> error("Keel is misconfigured. No resource kinds registered.")
      }
    }

  fun identify(kind: ResourceKind): Class<out ResourceSpec> =
    kinds.find { it.kind == kind }?.specClass ?: throw UnsupportedKind(kind)

  /**
   * Constructor useful for tests so they can just wire in using varargs.
   */
  constructor(vararg kinds: SupportedKind<*>) : this(null) {
    _kinds = kinds.toList()
  }
}
