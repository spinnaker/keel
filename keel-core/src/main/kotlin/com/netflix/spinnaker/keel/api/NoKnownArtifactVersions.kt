package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact

class NoKnownArtifactVersions(artifact: DeliveryArtifact) : RuntimeException("No versions for ${artifact.type} artifact ${artifact.name} are known")
