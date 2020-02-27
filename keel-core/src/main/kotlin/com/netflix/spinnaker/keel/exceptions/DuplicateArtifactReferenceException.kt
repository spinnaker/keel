package com.netflix.spinnaker.keel.exceptions

class DuplicateArtifactReferenceException(
  private val refs: List<String>
) : ValidationException(
  "Multiple artifacts are using the same string(s) as a reference: $refs. " +
    "Please ensure each artifact has a unique reference."
)
