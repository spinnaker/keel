package com.netflix.spinnaker.keel.exceptions

class DuplicateArtifactReferenceException(
  private val artifactNameToRef: Map<String, String>
) : ValidationException(
  "Multiple artifacts are using the same string as a reference: $artifactNameToRef. " +
    "Please ensure each artifact has a unique reference."
)
