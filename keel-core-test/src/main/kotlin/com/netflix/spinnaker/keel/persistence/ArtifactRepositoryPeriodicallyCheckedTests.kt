package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.artifacts.DEBIAN
import com.netflix.spinnaker.keel.api.artifacts.ArtifactSpec
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.artifacts.DebianArtifactSpec

abstract class ArtifactRepositoryPeriodicallyCheckedTests<S : ArtifactRepository> :
  PeriodicallyCheckedRepositoryTests<ArtifactSpec, S>() {

  override val descriptor = "artifact"

  override val createAndStore: Fixture<ArtifactSpec, S>.(count: Int) -> Collection<ArtifactSpec> = { count ->
    (1..count)
      .map { i ->
        DebianArtifactSpec(
          name = "artifact-$i",
          deliveryConfigName = "delivery-config-$i",
          reference = "ref-$i",
          vmOptions = VirtualMachineOptions(
            baseOs = "bionic-classic",
            regions = setOf("us-west-2", "us-east-1")
          )
        )
          .also(subject::register)
      }
  }

  override val updateOne: Fixture<ArtifactSpec, S>.() -> ArtifactSpec = {
    subject
      .get("artifact-1", DEBIAN, "ref-1", "delivery-config-1")
      .let { it as DebianArtifactSpec }
      .copy(reference = "my-delightful-artifact")
      .also(subject::register)
  }
}
