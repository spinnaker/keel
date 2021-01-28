package com.netflix.spinnaker.keel.lemur

import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.EC2_APPLICATION_LOAD_BALANCER_V1_2
import com.netflix.spinnaker.keel.api.plugins.Resolver
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

@Component
@ConditionalOnBean(LemurService::class)
class LemurCertificateResolver(
  private val lemurService: LemurService
) : Resolver<ApplicationLoadBalancerSpec> {
  override val supportedKind = EC2_APPLICATION_LOAD_BALANCER_V1_2

  override fun invoke(resource: Resource<ApplicationLoadBalancerSpec>): Resource<ApplicationLoadBalancerSpec> =
    resource.copy(
      spec = resource.spec.copy(
        listeners = resource.spec.listeners.mapTo(mutableSetOf()) { listener ->
          listener.certificate?.let { name ->
            listener.copy(
              certificate = findCurrentCertificate(name)
            )
          } ?: listener
        }
      )
    )

  private fun findCurrentCertificate(name: String) =
    runBlocking {
      val certificate = lemurService.certificateByName(name).items.first()
      if (certificate.active) {
        certificate.name
      } else {
        certificate.replacedBy.first { it.active }.name
      }
    }
}
