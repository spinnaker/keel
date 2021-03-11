package com.netflix.spinnaker.keel.rest

import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.spinnaker.keel.graphql.DgsConstants
import com.netflix.spinnaker.keel.graphql.types.Application
import com.netflix.spinnaker.keel.graphql.types.Environment
import com.netflix.spinnaker.keel.graphql.types.EnvironmentState
import com.netflix.spinnaker.keel.graphql.types.Resource
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.services.EnvironmentService
import com.netflix.spinnaker.keel.api.Environment as KeelEnvironment
import com.netflix.spinnaker.keel.api.Resource as KeelResource
import com.netflix.spinnaker.keel.api.DeliveryConfig

@DgsComponent
class ApplicationFetcher(private val deliveryConfigRepository: DeliveryConfigRepository) {

  @DgsData(parentType = DgsConstants.QUERY.TYPE_NAME, field = DgsConstants.QUERY.Application)
  fun application(@InputArgument("appName") appName: String): Application {
    return deliveryConfigRepository.getByApplication(appName).toModel();
  }

  @DgsData(parentType = DgsConstants.APPLICATION.TYPE_NAME, field = DgsConstants.APPLICATION.Environments)
  fun environments(dfe: DgsDataFetchingEnvironment): List<Environment> {
    val app: Application = dfe.getSource()
    return deliveryConfigRepository.getByApplication(app.name).environments.map { it.toModel() }
  }


  fun KeelEnvironment.toModel() =
    Environment(
      name = name,
      isActive = true,
      history = emptyList(),
      currentState = EnvironmentState(version = "1", resources = resources.map { it.toModel() })
    )

  fun DeliveryConfig.toModel() =
    Application(name = application, account = serviceAccount, environments = emptyList())

  fun KeelResource<*>.toModel() = Resource(id = id, kind = kind.toString())

}
