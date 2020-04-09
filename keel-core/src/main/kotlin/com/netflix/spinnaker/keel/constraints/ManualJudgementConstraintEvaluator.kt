package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.core.api.ManualJudgementConstraint
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.rest.AuthorizationSupport
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class ManualJudgementConstraintEvaluator(
  repository: KeelRepository,
  private val clock: Clock,
  override val eventPublisher: ApplicationEventPublisher,
  override val authorizationSupport: AuthorizationSupport
) : StatefulConstraintEvaluator<ManualJudgementConstraint>(repository, authorizationSupport) {

  override val supportedType = SupportedConstraintType<ManualJudgementConstraint>("manual-judgement")

  override fun canPromote(
    artifact: DeliveryArtifact,
    version: String,
    deliveryConfig: DeliveryConfig,
    targetEnvironment: Environment,
    constraint: ManualJudgementConstraint,
    state: ConstraintState
  ): Boolean {
    if (state.failed()) {
      return false
    }

    if (state.timedOut(constraint.timeout, clock.instant())) {
      repository
        .storeConstraintState(
          state.copy(
            status = ConstraintStatus.FAIL,
            comment = "Timed out after ${constraint.timeout}"
          )
        )

      // TODO: Emit event
      return false
    }

    return state.passed()
  }
}
