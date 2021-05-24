package com.netflix.spinnaker.keel.rest.dgs

import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.StatefulConstraint
import com.netflix.spinnaker.keel.api.constraints.ConstraintState
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.api.constraints.StatefulConstraintEvaluator
import com.netflix.spinnaker.keel.api.plugins.ConstraintEvaluator
import com.netflix.spinnaker.keel.constraints.AllowedTimesConstraintAttributes
import com.netflix.spinnaker.keel.constraints.AllowedTimesConstraintEvaluator
import com.netflix.spinnaker.keel.constraints.DependsOnConstraintAttributes
import com.netflix.spinnaker.keel.core.api.ArtifactVersionVetoData
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.MANUAL_JUDGEMENT_CONSTRAINT_TYPE
import com.netflix.spinnaker.keel.core.api.TimeWindowConstraint
import com.netflix.spinnaker.keel.graphql.types.MdConstraint
import com.netflix.spinnaker.keel.graphql.types.MdConstraintStatus
import com.netflix.spinnaker.keel.graphql.types.MdVersionVetoed
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.services.removePrivateConstraintAttrs
import org.dataloader.BatchLoaderEnvironment
import org.dataloader.MappedBatchLoaderWithContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Loads all constraint states for the given versions
 */
@DgsDataLoader(name = VetoedDataLoader.Descriptor.name)
class VetoedDataLoader(
  private val keelRepository: KeelRepository,
) : MappedBatchLoaderWithContext<EnvironmentArtifactAndVersion, MdVersionVetoed> {

  object Descriptor {
    const val name = "artifact-version-vetoed"
  }

  override fun load(keys: MutableSet<EnvironmentArtifactAndVersion>, environment: BatchLoaderEnvironment):
    CompletionStage<MutableMap<EnvironmentArtifactAndVersion, MdVersionVetoed>> {
    val context: ApplicationContext = DgsContext.getCustomContext(environment)
    return CompletableFuture.supplyAsync {
      val results: MutableMap<EnvironmentArtifactAndVersion, MdVersionVetoed> = mutableMapOf()
      val vetoed = keelRepository.vetoedEnvironmentVersions(context.getConfig())

      vetoed.forEach { envArtifact ->
        envArtifact.versions.map { version ->
          results.put(
            EnvironmentArtifactAndVersion(environmentName = envArtifact.targetEnvironment, artifactReference = envArtifact.artifact.reference, version = version.version),
            version.toDgs()
          )
        }
      }
      results
    }
  }
}

fun ArtifactVersionVetoData.toDgs() =
  MdVersionVetoed(vetoedBy = vetoedBy, vetoedAt = vetoedAt, comment = comment)
