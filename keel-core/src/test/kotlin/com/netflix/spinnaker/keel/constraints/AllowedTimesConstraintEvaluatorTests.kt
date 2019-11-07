package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.ArtifactType
import com.netflix.spinnaker.keel.api.DeliveryArtifact
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.TimeWindow
import com.netflix.spinnaker.keel.api.TimeWindowConstraint
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.failed
import strikt.assertions.isA
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import java.lang.IllegalArgumentException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

internal class AllowedTimesConstraintEvaluatorTests : JUnit5Minutests {
  companion object {
    // In America/Los_Angeles, this was 1pm on a Thursday
    val businessHoursClock: Clock = Clock.fixed(Instant.parse("2019-10-31T20:00:00Z"), ZoneId.of("UTC"))
    // In America/Los_Angeles, this was 1pm on a Saturday
    val weekendClock: Clock = Clock.fixed(Instant.parse("2019-11-02T20:00:00Z"), ZoneId.of("UTC"))
    // In America/Los_Angeles, this was 6am on a Monday
    val mondayClock: Clock = Clock.fixed(Instant.parse("2019-10-28T13:00:00Z"), ZoneId.of("UTC"))
  }

  data class Fixture(
    val clock: Clock,
    val constraint: TimeWindowConstraint
  ) {
    val artifact = DeliveryArtifact("fnord", ArtifactType.DEB)
    val environment = Environment(
      name = "prod",
      constraints = setOf(constraint)
    )
    val manifest = DeliveryConfig(
      name = "my-manifest",
      application = "fnord",
      artifacts = setOf(artifact),
      environments = setOf(environment)
    )

    private val dynamicConfigService: DynamicConfigService = mockk(relaxUnitFun = true) {
      every {
        getConfig(String::class.java, "default.time-zone", any())
      } returns "UTC"
    }

    val subject = AllowedTimesConstraintEvaluator(clock, dynamicConfigService)
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture(
        clock = businessHoursClock,
        constraint = TimeWindowConstraint(
          listOf(
            TimeWindow(
              days = "Monday-Tuesday,Thursday-Friday",
              hours = "09-16"
            )
          ),
          tz = "America/Los_Angeles"
        )
      )
    }

    test("canPromote when in-window") {
      expectThat(subject.canPromote(artifact, "1.1", manifest, environment.name))
        .isTrue()
    }

    context("multiple time windows") {
      fixture {
        Fixture(
          // In America/Los_Angeles, this was 1pm on a Thursday
          clock = businessHoursClock,
          constraint = TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "Monday",
                hours = "11-16"
              ),
              TimeWindow(
                days = "Wednesday-Friday",
                hours = "13"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("canPromote due to second window") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment.name))
          .isTrue()
      }
    }

    context("outside of time window") {
      fixture {
        Fixture(
          clock = weekendClock,
          constraint = TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "Monday-Friday",
                hours = "11-16"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("can't promote, out of window") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment.name))
          .isFalse()
      }
    }

    context("weekdays alias") {
      fixture {
        Fixture(
          clock = weekendClock,
          constraint = TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "weekdays"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("can't promote, not a weekday") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment.name))
          .isFalse()
      }
    }

    context("weekend alias") {
      fixture {
        Fixture(
          clock = weekendClock,
          constraint = TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "weekends"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("can't promote, not a weekday") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment.name))
          .isTrue()
      }
    }

    context("environment constraint can use short days for default locale") {
      fixture {
        Fixture(
          clock = businessHoursClock,
          constraint = TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "mon-fri",
                hours = "11-16"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("in window with short day format") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment.name))
          .isTrue()
      }
    }

    context("allowed-times constraint with default time zone") {
      fixture {
        Fixture(
          clock = businessHoursClock,
          constraint = TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "mon-fri",
                hours = "11-16"
              )
            )
          )
        )
      }

      test("11-16 is outside of allowed times when defaulting tz to UTC") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment.name))
          .isFalse()
      }
    }

    context("wrap around hours and days") {
      fixture {
        Fixture(
          clock = mondayClock,
          constraint = TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "sat-tue",
                hours = "23-10"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("in window due to day and hour wrap-around") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment.name))
          .isTrue()
      }
    }

    context("window is validated at construction") {
      test("invalid day range") {
        expectCatching {
          TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "mon-frizzay",
                hours = "11-16"
              )
            ),
            tz = "America/Los_Angeles"
          )
        }
          .failed()
          .isA<IllegalArgumentException>()
      }

      test("invalid hour range") {
        expectCatching {
          TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "weekdays",
                hours = "11-161"
              )
            ),
            tz = "America/Los_Angeles"
          )
        }
          .failed()
          .isA<IllegalArgumentException>()
      }

      test("invalid tz") {
        expectCatching {
          TimeWindowConstraint(
            listOf(
              TimeWindow(
                days = "weekdays",
                hours = "11-16"
              )
            ),
            tz = "America/Los_Spingeles"
          )
        }
          .failed()
          .isA<IllegalArgumentException>()
      }
    }
  }
}
