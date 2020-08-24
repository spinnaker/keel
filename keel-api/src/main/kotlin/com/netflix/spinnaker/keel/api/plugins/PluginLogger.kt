package com.netflix.spinnaker.keel.api.plugins

import java.lang.annotation.Documented
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.FIELD

/**
 * Marker annotation that allows the hosting service to explicitly inject SLF4J [Logger] into plugin beans.
 */
@Retention(RUNTIME)
@Target(FIELD)
@Documented
annotation class PluginLogger
