package com.netflix.spinnaker.keel.core.api

import com.netflix.spinnaker.keel.api.Constraint

/**
 * A constraint that ensures only artifacts of the appropriate type are ever approved
 * for deployment into an environment.
 */
class ArtifactTypeConstraint : Constraint("artifact-type")
