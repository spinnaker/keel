package com.netflix.spinnaker.keel.extensions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DefaultExtensionRegistry(
  private val mappers: List<ObjectMapper>
) : ExtensionRegistry {
  private val baseToExtensionTypes = mutableMapOf<Class<*>, MutableMap<String, Class<*>>>()
  private val classBySimpleName = Comparator<Class<*>> { left, right ->
    left.simpleName.compareTo(right.simpleName)
  }

  override fun <BASE> register(
    baseType: Class<BASE>,
    extensionType: Class<out BASE>,
    discriminator: String
  ) {
    baseToExtensionTypes
      .getOrPut(baseType, ::mutableMapOf)
      .also { it[discriminator] = extensionType }
    log.info("Registering extension \"$discriminator\" for ${baseType.simpleName} using ${extensionType.simpleName}")
    mappers.forEach {
      it.registerSubtypes(NamedType(extensionType, discriminator))
    }
  }

  fun forEachExtension(callback: (Class<*>, Class<*>, String) -> Unit) {
    baseToExtensionTypes
      .keys
      .sortedWith(classBySimpleName)
      .forEach { baseType ->
        forEachExtensionOf(baseType) { extensionType, discriminator ->
          callback(baseType, extensionType, discriminator)
        }
      }
  }

  fun <BASE> forEachExtensionOf(baseType: Class<BASE>, callback: (Class<out BASE>, String) -> Unit) {
    (baseToExtensionTypes[baseType] ?: emptyMap<String, Class<out BASE>>())
      .toSortedMap()
      .forEach { (discriminator, extensionType) ->
        @Suppress("UNCHECKED_CAST")
        callback(extensionType as Class<out BASE>, discriminator)
      }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

fun <EXTENSION> ObjectMapper.registerExtension(
  extensionType: Class<EXTENSION>,
  discriminator: String
) {
  registerSubtypes(NamedType(extensionType, discriminator))
}

fun <EXTENSION> Iterable<ObjectMapper>.registerExtension(
  extensionType: Class<EXTENSION>,
  discriminator: String
) {
  forEach {
    it.registerExtension(extensionType, discriminator)
  }
}

inline fun <reified EXTENSION> Iterable<ObjectMapper>.registerExtension(
  discriminator: String
) {
  registerExtension(EXTENSION::class.java, discriminator)
}
