package com.netflix.spinnaker.keel.exceptions

class NonexistentArtifactReferenceException(
  reference: String
) : ValidationException(
  "Config uses an artifact reference that does not correspond to any artifacts: $reference."
)
