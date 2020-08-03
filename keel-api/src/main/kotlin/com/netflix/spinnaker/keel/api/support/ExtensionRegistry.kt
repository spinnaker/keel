package com.netflix.spinnaker.keel.api.support

/**
 * Registers an extension to a model so that it may be used in a delivery config.
 */
interface ExtensionRegistry {
  fun <BASE> register(
    baseType: Class<BASE>,
    extensionType: Class<out BASE>,
    discriminator: String
  )
}

inline fun <reified BASE> ExtensionRegistry.register(
  extensionType: Class<out BASE>,
  discriminator: String
) {
  register(BASE::class.java, extensionType, discriminator)
}

inline fun <reified BASE, reified EXTENSION : BASE> ExtensionRegistry.register(
  discriminator: String
) {
  register(BASE::class.java, EXTENSION::class.java, discriminator)
}
