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
import com.netflix.spinnaker.keel.persistence.ApplicationRepository

@DgsComponent
class ApplicationFetcher(private val applicationRepository: ApplicationRepository) {

  @DgsData(parentType = DgsConstants.QUERY.TYPE_NAME, field = DgsConstants.QUERY.Application)
  fun application(@InputArgument("appName") appName: String): Application {
    return applicationRepository.getApplication(appName);
  }

  @DgsData(parentType = DgsConstants.APPLICATION.TYPE_NAME, field = DgsConstants.APPLICATION.Environments)
  fun environments(dfe: DgsDataFetchingEnvironment): List<Environment> {
    val app: Application = dfe.getSource()
    return applicationRepository.getEnvironments(app.uid)
  }

//  @DgsData(parentType = DgsConstants.ENVIRONMENT.TYPE_NAME, field = DgsConstants.ENVIRONMENT.CurrentState)
//  fun environmentState(dfe: DgsDataFetchingEnvironment): EnvironmentState {
//    return EnvironmentState(version = "1")
//  }

  @DgsData(parentType = DgsConstants.ENVIRONMENT.TYPE_NAME, field = DgsConstants.ENVIRONMENT.Resources)
  fun resource(dfe: DgsDataFetchingEnvironment): List<Resource> {
    val environment: Environment = dfe.getSource()
    return applicationRepository.getResources(environment.uid)
  }

  @DgsData(parentType = DgsConstants.RESOURCE.TYPE_NAME, field = DgsConstants.RESOURCE.Status)
  fun resourceStatus(dfe: DgsDataFetchingEnvironment): String {
    val resource: Resource = dfe.getSource()
    return applicationRepository.getResourceStatus(resource.ref).toString()
  }

}
