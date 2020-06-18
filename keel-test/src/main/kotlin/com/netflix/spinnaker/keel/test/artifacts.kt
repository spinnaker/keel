package com.netflix.spinnaker.keel.test

import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy
import com.netflix.spinnaker.keel.api.plugins.ArtifactPublisher
import com.netflix.spinnaker.keel.api.support.SpringEventPublisherBridge
import com.netflix.spinnaker.keel.artifact.DebianArtifactPublisher
import com.netflix.spinnaker.keel.artifact.DockerArtifactPublisher
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import io.mockk.mockk

class DummyArtifact(
  override val name: String = "fnord",
  override val deliveryConfigName: String? = "manifest",
  override val reference: String = "fnord"
) : DeliveryArtifact() {
  override val type: ArtifactType = "dummy"
  override val versioningStrategy = object : VersioningStrategy() {
    override val comparator: Comparator<String> = Comparator.naturalOrder()
    override val type = "dummy"
  }
}

fun defaultArtifactPublishers(): List<ArtifactPublisher<*>> {
  val artifactService: ArtifactService = mockk(relaxUnitFun = true)
  val clouddriverService: CloudDriverService = mockk(relaxUnitFun = true)
  val eventBridge: SpringEventPublisherBridge = mockk(relaxUnitFun = true)
  return listOf(
    DebianArtifactPublisher(eventBridge, artifactService),
    DockerArtifactPublisher(eventBridge, clouddriverService)
  )
}
