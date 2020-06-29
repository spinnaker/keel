package com.netflix.spinnaker.keel.api.docs

import kotlin.annotation.AnnotationTarget.CLASS

@Target(CLASS)
// TODO: not a great name
annotation class Literal(val type: String = "string", val value: String)
