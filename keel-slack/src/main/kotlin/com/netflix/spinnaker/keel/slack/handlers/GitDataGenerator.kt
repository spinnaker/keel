package com.netflix.spinnaker.keel.slack.handlers

import com.netflix.spinnaker.keel.api.ScmInfo
import com.netflix.spinnaker.keel.api.artifacts.GitMetadata
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.artifacts.getScmBaseLink
import com.slack.api.model.kotlin_extension.block.SectionBlockBuilder
import org.apache.logging.log4j.util.Strings
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class GitDataGenerator(
  private val scmInfo: ScmInfo,
  @Value("\${spinnaker.baseUrl}") private val spinnakerBaseUrl: String
) {

  fun generateRepoLink(gitMetadata: GitMetadata?): String {
    if (gitMetadata != null) {
      val baseScmUrl = gitMetadata.commitInfo?.link?.let { getScmBaseLink(scmInfo, it) }
      return "$baseScmUrl/projects/${gitMetadata.project}/repos/${gitMetadata.repo?.name}"
    } else return Strings.EMPTY
  }

  /**
   * generateGitData will create a slack section blocks, which looks like:
   * "spkr/keel › PR#7 › master › c25a357"
   * Or: "spkr/keel › master › c25a358" (no PR data)
   * Each component will have the corresponding link attached to SCM
   */
  fun generateData(sectionBlockBuilder: SectionBlockBuilder, application: String, artifact: PublishedArtifact): SectionBlockBuilder {
//  protected fun SectionBlockBuilder.generateGitData(application: String, artifact: PublishedArtifact) {
    with(sectionBlockBuilder) {
      var details = ""
      val artifactUrl = "$spinnakerBaseUrl/#/applications/${application}/environments/${artifact.reference}/${artifact.version}"

      if (artifact.gitMetadata != null) {
        val repoLink = generateRepoLink(artifact.gitMetadata)
        with(artifact.gitMetadata) {

          details += "<$repoLink|${this?.project}/" +
            "${this?.repo?.name}> › " +
            "<$repoLink/branches|${this?.branch}> › "

          //if the commit is not a part of a PR, don't include PR's data
          if (Strings.isNotEmpty(this?.pullRequest?.number)) {
            details += "<${this?.pullRequest?.url}|PR#${this?.pullRequest?.number}> ›"
          }
          markdownText(details +
            "<${this?.commitInfo?.link}|${this?.commitInfo?.sha?.substring(0, 7)}>")
        }
      }
      accessory {
        button {
          text("More...")
          //TODO: figure out which action id to send here
          actionId("button-action")
          url(artifactUrl)
        }
      }
      return this
    }
  }

}
