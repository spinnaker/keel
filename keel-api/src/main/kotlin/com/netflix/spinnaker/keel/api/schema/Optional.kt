package com.netflix.spinnaker.keel.api.schema

import kotlin.annotation.AnnotationTarget.PROPERTY

/**
 * Explicitly marks a property as optional in the API schema, overriding whatever heuristic would
 * normally apply.
 */
@Target(PROPERTY)
annotation class Optional
