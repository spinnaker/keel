package com.netflix.spinnaker.keel.exceptions

open class ValidationException(
  val msg: String
) : RuntimeException(msg)

class DuplicateResourceIdException(
  val ids: List<String>,
  val envsToResources: Map<String, List<String>>
) : ValidationException(
  "Resources with ids $ids exist in more than one environment ($envsToResources). " +
    "Please ensure each resource has a unique id."
)
