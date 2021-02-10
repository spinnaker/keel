package com.netflix.spinnaker.keel.slack.callbacks

import com.netflix.spinnaker.keel.api.constraints.ConstraintState
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.slack.SlackService
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.ZoneId

class ManualJudgmentCallbackHandlerTests : JUnit5Minutests {

  class Fixture {
    val repository: KeelRepository = mockk()
    val slackService: SlackService = mockk()

    val clock: MutableClock = MutableClock(
      Instant.parse("2020-03-25T00:00:00.00Z"),
      ZoneId.of("UTC")
    )

    val slackCallbackResponse =
      SlackCallbackHandler.SlackCallbackResponse(
        type = "",
        user = SlackCallbackHandler.SlackUser(
          username = "keel",
          id = "01234",
          name = "keel-user"
        ),
        actions = listOf(SlackCallbackHandler.SlackAction(
          type = "button",
          action_id = "01EQW0XKNR5H2NMPJXP020EQXE:OVERRIDE_PASS:MANUAL_JUDGMENT",
          value = "OVERRIDE_PASS",
          action_ts = "now"
        )),
        response_url = "",
        message = mapOf("blocks" to listOf<String>())
      )

    val pendingManualJudgement = ConstraintState(
      "delivery_config",
      "testing",
      "1.0.0",
      "my-debian",
      "manual-judgement",
      ConstraintStatus.PENDING
    )

    val subject = ManualJudgmentCallbackHandler(clock, repository, slackService)
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture()
    }

    context("handling manual judgment response") {
      before {
        every {
          repository.getConstraintStateById(any())
        } returns pendingManualJudgement

        every {
          slackService.getEmailByUserId(any())
        } returns "keel@keel"

        every {
          repository.storeConstraintState(any())
        } just Runs

        every {
          slackService.respondToCallback(any(), any(), any())
        } just Runs

      }

      test("update status correctly") {
        subject.handleMJResponse(slackCallbackResponse)
        verify (exactly = 1){
          repository.storeConstraintState(
           pendingManualJudgement.copy(
             status = ConstraintStatus.OVERRIDE_PASS,
             judgedBy = "keel@keel"
           )
          )
        }

      }
    }
  }
}
