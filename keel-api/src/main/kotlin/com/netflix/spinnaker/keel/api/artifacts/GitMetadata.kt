package com.netflix.spinnaker.keel.api.artifacts

/**
 * The git metadata of an artifact.
 */
data class GitMetadata(
  val commit: String, // commit hash, can be short or long sha
  val author: String? = null,
  val linkToCommit: String? = null,
  val repoName: String? = null, // the repository name, like "myApp"
  val projectName: String? = null, // the project name, like SPKR
  val commitMessage: String? = null
)
