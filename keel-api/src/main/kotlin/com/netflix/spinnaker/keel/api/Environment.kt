package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.api.postdeploy.PostDeployAction

data class Environment(
  val name: String,
  val resources: Set<Resource<*>> = emptySet(),
  val constraints: Set<Constraint> = emptySet(),
  val verifyWith: List<Verification> = emptyList(),
  val notifications: Set<NotificationConfig> = emptySet(), // applies to each resource
  val postDeploy: List<PostDeployAction> = emptyList(),
  val isPreview: Boolean = false
) {
  val resourceIds: Set<String>
    get() = resources.mapTo(mutableSetOf(), Resource<*>::id)
}

val Set<Constraint>.anyStateful: Boolean
  get() = any { it is StatefulConstraint }

val Set<Constraint>.statefulCount: Int
  get() = filterIsInstance<StatefulConstraint>().size
