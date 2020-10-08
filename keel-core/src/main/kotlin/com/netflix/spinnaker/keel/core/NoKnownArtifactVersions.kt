package com.netflix.spinnaker.keel.core

import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec
import com.netflix.spinnaker.kork.exceptions.IntegrationException

class NoKnownArtifactVersions(artifact: ArtifactSpec) :
  IntegrationException("No versions for ${artifact.type} artifact ${artifact.name} are known")
