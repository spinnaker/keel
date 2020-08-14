package com.netflix.spinnaker.model

/**
 * An immutable data class that represents a published software artifact in the Spinnaker ecosystem.
 *
 * This class mirrors [com.netflix.spinnaker.igor.build.model.GenericBuild], but without all the Jackson baggage.
 */

data class Build(
  val building: Boolean = false,
  val fullDisplayName: String? = null,
  val name: String? = null,
  val number: Int = 0,
  val duration: Int? = null,
  /** String representation of time in nanoseconds since Unix epoch  */
   val timestamp: String? = null,

  val result: Any? = null,
  val artifacts: List<Any>? = null,
  val testResults: List<Any>? = null,
  val url: String? = null,
  val id: String? = null,

  val properties: Map<String, Any?>? = null
)
