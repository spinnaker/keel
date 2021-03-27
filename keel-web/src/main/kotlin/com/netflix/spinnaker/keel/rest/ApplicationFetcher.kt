package com.netflix.spinnaker.keel.rest

import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.graphql.DgsConstants
import com.netflix.spinnaker.keel.graphql.types.Environment
import com.netflix.spinnaker.keel.graphql.types.Resource
import com.netflix.spinnaker.keel.graphql.types.Application
import com.netflix.spinnaker.keel.graphql.types.Constraint
import com.netflix.spinnaker.keel.graphql.types.Artifact
import com.netflix.spinnaker.keel.persistence.ApplicationRepository
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.services.ResourceStatusService
import com.netflix.spinnaker.kork.exceptions.SystemException
import com.netflix.spinnaker.keel.api.Resource as KeelResource
import com.netflix.spinnaker.keel.api.Environment as KeelEnvironment
import java.util.stream.Collectors

import graphql.execution.DataFetcherResult
import java.util.function.Function


@DgsComponent
class ApplicationFetcher(
  private val applicationRepository: ApplicationRepository,
  private val deliveryConfigRepository: DeliveryConfigRepository,
  private val resourceStatusService: ResourceStatusService
) {

  @DgsData(parentType = DgsConstants.QUERY.TYPE_NAME, field = DgsConstants.QUERY.Application)
  fun application(@InputArgument("appName") appName: String): DataFetcherResult<Application> {
    val config = deliveryConfigRepository.getByApplication(appName)
    config.uid ?: throw SystemException("application id should not be null")
    return DataFetcherResult.newResult<Application>().data(
      Application(
        uid = config.uid.toString(),
        name = config.name,
        account = config.serviceAccount,
        environments = emptyList()
      )
    ).localContext(config).build()
  }

  @DgsData(parentType = DgsConstants.APPLICATION.TYPE_NAME, field = DgsConstants.APPLICATION.Environments)
  fun environments(dfe: DgsDataFetchingEnvironment): List<Environment> {
    val config: DeliveryConfig = dfe.getLocalContext()
    return config.environments.map {
      Environment(name = it.name, resources = emptyList(), constraints = emptyList(), artifacts = emptyList())
    }
  }

  private fun getCurrentEnvironmentFrom(dfe: DgsDataFetchingEnvironment): KeelEnvironment {
    val source: Environment = dfe.getSource()
    val config: DeliveryConfig = dfe.getLocalContext()
    val environment = config.environments.find {
      it.name == source.name
    }
    environment ?: throw SystemException("environment ${source.name} is missing")
    return environment
  }

  @DgsData(parentType = DgsConstants.ENVIRONMENT.TYPE_NAME, field = DgsConstants.ENVIRONMENT.Resources)
  fun resource(dfe: DgsDataFetchingEnvironment): DataFetcherResult<List<Resource>> {
    val config: DeliveryConfig = dfe.getLocalContext()
    val environment = getCurrentEnvironmentFrom(dfe)
    return DataFetcherResult.newResult<List<Resource>>().data(environment.resources.map {
      Resource(id = it.id, kind = it.kind.toString(),
        artifact = it.findAssociatedArtifact(config)?.let { rawArtifact ->
        Artifact(
          name = rawArtifact.name,
          type = rawArtifact.type,
          reference = rawArtifact.reference,
        )
      })
    }).localContext(environment.resources.map { it.id to it }.toMap())
      .build()
  }

  @DgsData(parentType = DgsConstants.RESOURCE.TYPE_NAME, field = DgsConstants.RESOURCE.Status)
  fun resourceStatus(dfe: DgsDataFetchingEnvironment): String {
    val resource: Resource = dfe.getSource()
    return applicationRepository.getResourceStatus(resource.id).toString()
  }

  @DgsData(parentType = DgsConstants.ENVIRONMENT.TYPE_NAME, field = DgsConstants.ENVIRONMENT.Constraints)
  fun constraint(dfe: DgsDataFetchingEnvironment): List<Constraint> {
    val environment = getCurrentEnvironmentFrom(dfe)
    return environment.constraints.map { Constraint(type = it.type) }
  }
}
