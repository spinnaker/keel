package com.netflix.spinnaker.keel.api

import io.swagger.annotations.ApiModel
import io.swagger.annotations.ApiModelProperty

/**
 * Implemented by all resource specs.
 */
@ApiModel(description = "the specification of a resource")
interface ResourceSpec {

  /**
   * The formal resource name. This is combined with the resource's API version prefix and kind to
   * form the fully-qualified [ResourceId].
   *
   * This can be a property that is part of the spec, or derived from other properties. If the
   * latter remember to annotate the overridden property with
   * [com.fasterxml.jackson.annotation.JsonIgnore].
   */
  @get:ApiModelProperty(hidden = true)
  val id: String

  /**
   * The Spinnaker application this resource belongs to.
   *
   * This can be a property that is part of the spec, or derived from other properties. If the
   * latter remember to annotate the overridden property with
   * [com.fasterxml.jackson.annotation.JsonIgnore].
   */
  @get:ApiModelProperty(hidden = true)
  val application: String
}
