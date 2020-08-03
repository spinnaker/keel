package com.netflix.spinnaker.keel.apidocs

import com.netflix.spinnaker.keel.api.Constraint
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import com.netflix.spinnaker.keel.api.support.extensionsOf
import io.swagger.v3.oas.models.media.Schema
import org.springframework.stereotype.Component

/**
 * Adds [Schema]s for the available sub-types of [Constraint]. This cannot be done with
 * annotations on [Constraint] as the sub-types are not known at compile time.
 */
@Component
class ConstraintModelConverter(
  private val extensionRegistry: ExtensionRegistry
) : SubtypesModelConverter<Constraint>(Constraint::class.java) {

  override val subTypes: List<Class<out Constraint>>
    get() = mapping.values.toList()

  override val discriminator: String? = Constraint::type.name

  override val mapping: Map<String, Class<out Constraint>>
    get() = extensionRegistry.extensionsOf()
}
