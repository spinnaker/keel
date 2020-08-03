package com.netflix.spinnaker.keel.apidocs

import com.netflix.spinnaker.keel.api.ec2.InstanceProvider
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import com.netflix.spinnaker.keel.api.support.extensionsOf
import org.springframework.stereotype.Component

@Component
class InstanceProviderModelConverter(
  private val extensionRegistry: ExtensionRegistry
) : SubtypesModelConverter<InstanceProvider>(InstanceProvider::class.java) {

  override val subTypes: List<Class<out InstanceProvider>>
    get() = mapping.values.toList()

  override val discriminator: String? = InstanceProvider::type.name

  override val mapping: Map<String, Class<out InstanceProvider>>
    get() = extensionRegistry.extensionsOf()
}
