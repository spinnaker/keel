package com.netflix.spinnaker.keel.rest.dgs

import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.exceptions.DgsEntityNotFoundException
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.action.ActionType
import com.netflix.spinnaker.keel.artifacts.ArtifactVersionLinks
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.graphql.DgsConstants
import com.netflix.spinnaker.keel.graphql.types.MdAction
import com.netflix.spinnaker.keel.graphql.types.MdApplication
import com.netflix.spinnaker.keel.graphql.types.MdArtifact
import com.netflix.spinnaker.keel.graphql.types.MdArtifactStatusInEnvironment
import com.netflix.spinnaker.keel.graphql.types.MdArtifactVersionInEnvironment
import com.netflix.spinnaker.keel.graphql.types.MdComparisonLinks
import com.netflix.spinnaker.keel.graphql.types.MdConstraint
import com.netflix.spinnaker.keel.graphql.types.MdEnvironment
import com.netflix.spinnaker.keel.graphql.types.MdEnvironmentState
import com.netflix.spinnaker.keel.graphql.types.MdLifecycleStep
import com.netflix.spinnaker.keel.graphql.types.MdPackageDiff
import com.netflix.spinnaker.keel.graphql.types.MdPinnedVersion
import com.netflix.spinnaker.keel.graphql.types.MdResource
import com.netflix.spinnaker.keel.graphql.types.MdResourceActuationState
import com.netflix.spinnaker.keel.graphql.types.MdResourceActuationStatus
import com.netflix.spinnaker.keel.graphql.types.MdVersionVeto
import com.netflix.spinnaker.keel.pause.ActuationPauser
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.NoDeliveryConfigForApplication
import com.netflix.spinnaker.keel.services.ResourceStatusService
import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import org.dataloader.DataLoader
import java.util.concurrent.CompletableFuture

/**
 * Fetches details about an application, as defined in [schema.graphql]
 *
 * Loads the static data from a delivery config.
 * Adds supplemental data using functions to fetch more information.
 */
@DgsComponent
class ApplicationFetcher(
  private val keelRepository: KeelRepository,
  private val resourceStatusService: ResourceStatusService,
  private val actuationPauser: ActuationPauser,
  private val artifactVersionLinks: ArtifactVersionLinks,
  private val applicationFetcherSupport: ApplicationFetcherSupport
) {

  @DgsData(parentType = DgsConstants.QUERY.TYPE_NAME, field = DgsConstants.QUERY.Application)
  fun application(dfe: DataFetchingEnvironment, @InputArgument("appName") appName: String): MdApplication {
    val config = try {
      keelRepository.getDeliveryConfigForApplication(appName)
    } catch (ex: NoDeliveryConfigForApplication) {
      throw DgsEntityNotFoundException(ex.message!!)
    }
    val context: ApplicationContext = DgsContext.getCustomContext(dfe)
    context.deliveryConfig = config
    val environments: List<MdEnvironment> = config.environments.sortedWith { env1, env2 ->
      when {
        env1.dependsOn(env2) -> -1
        env2.dependsOn(env1) -> 1
        env1.hasDependencies() && !env2.hasDependencies() -> -1
        env2.hasDependencies() && !env1.hasDependencies() -> 1
        else -> 0
      }
    }.map { env ->
      val artifacts = config.artifactsUsedIn(env.name).map { artifact ->
        MdArtifact(
          id = "${env.name}-${artifact.reference}",
          environment = env.name,
          name = artifact.name,
          reference = artifact.reference,
          type = artifact.type
        )
      }

      MdEnvironment(
        id = env.name,
        name = env.name,
        isPreview = env.isPreview,
        state = MdEnvironmentState(
          id = "${env.name}-state",
          artifacts = artifacts,
          resources = env.resources.map { it.toDgs(config, env.name) }
        )
      )
    }

    return MdApplication(
      id = config.application,
      name = config.application,
      account = config.serviceAccount,
      environments = environments
    )
  }

  @DgsData(parentType = DgsConstants.MDAPPLICATION.TYPE_NAME, field = DgsConstants.MDAPPLICATION.IsPaused)
  fun isPaused(dfe: DgsDataFetchingEnvironment): Boolean {
    val app: MdApplication = dfe.getSource()
    return actuationPauser.applicationIsPaused(app.name)
  }

  @DgsData(parentType = DgsConstants.MDRESOURCE.TYPE_NAME, field = DgsConstants.MDRESOURCE.State)
  fun resourceStatus(dfe: DgsDataFetchingEnvironment): MdResourceActuationState {
    val resource: MdResource = dfe.getSource()
    val state = resourceStatusService.getActuationState(resource.id)
    return MdResourceActuationState(
      status = MdResourceActuationStatus.valueOf(state.status.name),
      reason = state.reason,
      event = state.eventMessage
    )
  }

  @DgsData(parentType = DgsConstants.MDARTIFACT.TYPE_NAME, field = DgsConstants.MDARTIFACT.Versions)
  fun versions(
    dfe: DataFetchingEnvironment,
    @InputArgument("statuses", collectionType = MdArtifactStatusInEnvironment::class) statuses: List<MdArtifactStatusInEnvironment>?,
    @InputArgument("versions") versionIds: List<String>?,
    @InputArgument("limit") limit: Int?
  ): CompletableFuture<List<DataFetcherResult<MdArtifactVersionInEnvironment>>>? {
    val dataLoader: DataLoader<ArtifactAndEnvironment, List<MdArtifactVersionInEnvironment>> = dfe.getDataLoader(ArtifactInEnvironmentDataLoader.Descriptor.name)
    val artifact: MdArtifact = dfe.getSource()
    val config = applicationFetcherSupport.getDeliveryConfigFromContext(dfe)
    val applicationContext: ApplicationContext = DgsContext.getCustomContext(dfe)
    if (statuses != null && applicationContext.requestedStatuses == null) {
      applicationContext.requestedStatuses = statuses.toSet()
    }
    if (versionIds != null && applicationContext.requestedVersionIds == null) {
      applicationContext.requestedVersionIds = versionIds.toSet()
    }
    if (limit != null && applicationContext.requestedLimit == null) {
      applicationContext.requestedLimit = limit
    }

    val deliveryArtifact = config.matchingArtifactByReference(artifact.reference) ?: return null

    return dataLoader.load(
      ArtifactAndEnvironment(
        artifact = deliveryArtifact,
        environmentName = artifact.environment,
      )
    ).thenApply { versions ->
      versions.map { version ->
        DataFetcherResult.newResult<MdArtifactVersionInEnvironment>().data(version).localContext(version).build()
      }
    }
  }

  @DgsData(parentType = DgsConstants.MDGITMETADATA.TYPE_NAME, field = DgsConstants.MDGITMETADATA.ComparisonLinks)
  fun comparisonLinks(dfe: DataFetchingEnvironment): MdComparisonLinks? {
    val diffContext = applicationFetcherSupport.getDiffContext(dfe)

    with(diffContext) {
      return MdComparisonLinks(
        toPreviousVersion = if (previousVersion != currentVersion) {
          artifactVersionLinks.generateCompareLink(currentVersion.publishedArtifact, previousVersion?.publishedArtifact, deliveryArtifact)
        } else {
          null
        },
        toCurrentVersion = if (currentVersion != currentVersion) {
          artifactVersionLinks.generateCompareLink(currentVersion.publishedArtifact, currentVersion?.publishedArtifact, deliveryArtifact)
        } else {
          null
        }
      )
    }
  }

  @DgsData(
    parentType = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.TYPE_NAME,
    field = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.PackageDiff
  )
  fun packageDiff(dfe: DataFetchingEnvironment): MdPackageDiff? {
    return applicationFetcherSupport.getDebianPackageDiff(dfe)
  }

  @DgsData(parentType = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.TYPE_NAME, field = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.LifecycleSteps)
  fun lifecycleSteps(dfe: DataFetchingEnvironment): CompletableFuture<List<MdLifecycleStep>>? {
    val dataLoader: DataLoader<ArtifactAndVersion, List<MdLifecycleStep>> = dfe.getDataLoader(LifecycleEventsByVersionDataLoader.Descriptor.name)
    val artifact: MdArtifactVersionInEnvironment = dfe.getSource()
    val config = applicationFetcherSupport.getDeliveryConfigFromContext(dfe)
    val deliveryArtifact = config.matchingArtifactByReference(artifact.reference) ?: return null
    return dataLoader.load(
      ArtifactAndVersion(
        deliveryArtifact,
        artifact.version
      )
    )
  }

  @DgsData(parentType = DgsConstants.MDARTIFACT.TYPE_NAME, field = DgsConstants.MDARTIFACT.PinnedVersion)
  fun pinnedVersion(dfe: DataFetchingEnvironment): CompletableFuture<MdPinnedVersion>? {
    val dataLoader: DataLoader<PinnedArtifactAndEnvironment, MdPinnedVersion> = dfe.getDataLoader(PinnedVersionInEnvironmentDataLoader.Descriptor.name)
    val artifact: MdArtifact = dfe.getSource()
    val config = applicationFetcherSupport.getDeliveryConfigFromContext(dfe)
    val deliveryArtifact = config.matchingArtifactByReference(artifact.reference) ?: return null
    return dataLoader.load(PinnedArtifactAndEnvironment(
      artifact = deliveryArtifact,
      environment = artifact.environment
    ))
  }

  @DgsData(parentType = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.TYPE_NAME, field = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.Constraints)
  fun artifactConstraints(dfe: DataFetchingEnvironment): CompletableFuture<List<MdConstraint>>? {
    val dataLoader: DataLoader<EnvironmentArtifactAndVersion, List<MdConstraint>> = dfe.getDataLoader(ConstraintsDataLoader.Descriptor.name)
    val artifact: MdArtifactVersionInEnvironment = dfe.getSource()
    return artifact.environment?.let { environmentName ->
      dataLoader.load(
        EnvironmentArtifactAndVersion(
          environmentName,
          artifact.reference,
          artifact.version
        )
      )
    }
  }

  @DgsData(parentType = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.TYPE_NAME, field = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.Verifications)
  fun artifactVerifications(dfe: DataFetchingEnvironment): CompletableFuture<List<MdAction>>? {
    val dataLoader: DataLoader<EnvironmentArtifactAndVersion, List<MdAction>> = dfe.getDataLoader(ActionsDataLoader.Descriptor.name)
    val artifact: MdArtifactVersionInEnvironment = dfe.getSource()
    return artifact.environment?.let { environmentName ->
      dataLoader.load(
        EnvironmentArtifactAndVersion(
          environmentName,
          artifact.reference,
          artifact.version,
          ActionType.VERIFICATION
        )
      )
    }
  }

  @DgsData(parentType = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.TYPE_NAME, field = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.PostDeploy)
  fun artifactPostDeploy(dfe: DataFetchingEnvironment): CompletableFuture<List<MdAction>>? {
    val dataLoader: DataLoader<EnvironmentArtifactAndVersion, List<MdAction>> = dfe.getDataLoader(ActionsDataLoader.Descriptor.name)
    val artifact: MdArtifactVersionInEnvironment = dfe.getSource()
    return artifact.environment?.let { environmentName ->
      dataLoader.load(
        EnvironmentArtifactAndVersion(
          environmentName,
          artifact.reference,
          artifact.version,
          ActionType.POST_DEPLOY
        )
      )
    }
  }

  @DgsData(parentType = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.TYPE_NAME, field = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.Resources)
  fun artifactResources(dfe: DataFetchingEnvironment): List<MdResource>? {
    val artifact: MdArtifactVersionInEnvironment = dfe.getSource()
    val config = applicationFetcherSupport.getDeliveryConfigFromContext(dfe)
    return artifact.environment?.let {
      config.resourcesUsing(artifact.reference, artifact.environment).map { it.toDgs(config, artifact.environment) }
    }
  }

  @DgsData(parentType = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.TYPE_NAME, field = DgsConstants.MDARTIFACTVERSIONINENVIRONMENT.Veto)
  fun versionVetoed(dfe: DataFetchingEnvironment): CompletableFuture<MdVersionVeto?>? {
    val config = applicationFetcherSupport.getDeliveryConfigFromContext(dfe)
    val dataLoader: DataLoader<EnvironmentArtifactAndVersion, MdVersionVeto?> = dfe.getDataLoader(VetoedDataLoader.Descriptor.name)
    val artifact: MdArtifactVersionInEnvironment = dfe.getSource()
    return artifact.environment?.let { environmentName ->
      dataLoader.load(
        EnvironmentArtifactAndVersion(
          environmentName,
          artifact.reference,
          artifact.version,
        )
      )
    }
  }


//  add function for putting the resources on the artifactVersion
}

fun Environment.dependsOn(another: Environment) =
  constraints.any { it is DependsOnConstraint && it.environment == another.name }

fun Environment.hasDependencies() =
  constraints.any { it is DependsOnConstraint }
