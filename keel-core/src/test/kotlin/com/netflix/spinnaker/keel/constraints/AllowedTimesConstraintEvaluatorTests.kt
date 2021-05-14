package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.PASS
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.core.api.TimeWindow
import com.netflix.spinnaker.keel.core.api.TimeWindowConstraint
import com.netflix.spinnaker.keel.core.api.TimeWindowNumeric
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import strikt.api.expect
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure
import strikt.assertions.isFalse
import strikt.assertions.isNotNull
import strikt.assertions.isTrue

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
    val artifact = DebianArtifact("fnord", vmOptions = VirtualMachineOptions(baseOs = "bionic", regions = setOf("us-west-2")))
    val environment = Environment(
      name = "prod",
      constraints = setOf(constraint)
    )
    val manifest = DeliveryConfig(
      name = "my-manifest",
      application = "fnord",
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(artifact),
      environments = setOf(environment)
    )

    private val dynamicConfigService: DynamicConfigService = mockk(relaxUnitFun = true) {
      every {
        getConfig(String::class.java, "default.time-zone", any())
      } returns "UTC"
    }

    val subject = AllowedTimesConstraintEvaluator(clock, dynamicConfigService, mockk())
  }

  fun tests() = rootContext<Fixture> {
    fixture {
      Fixture(
        clock = businessHoursClock,
        constraint = TimeWindowConstraint(
          windows = listOf(
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
      expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
        .isTrue()
    }

    context("multiple time windows") {
      fixture {
        Fixture(
          // In America/Los_Angeles, this was 1pm on a Thursday
          clock = businessHoursClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
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
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isTrue()
      }

      test("ui format is correct") {
        val windows = AllowedTimesConstraintEvaluator.toNumericTimeWindows(constraint)
        expect {
          that(windows.size).isEqualTo(2)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(setOf(1), IntRange(11,16).toSet()),
            TimeWindowNumeric(setOf(3,4,5), setOf(13))
          )
        }
      }
    }

    context("outside of time window") {
      fixture {
        Fixture(
          clock = weekendClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
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
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isFalse()
      }

      test("ui format is correct") {
        val windows = AllowedTimesConstraintEvaluator.toNumericTimeWindows(constraint)
        expect {
          that(windows.size).isEqualTo(1)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(IntRange(1,5).toSet(), IntRange(11,16).toSet()),
          )
        }
      }
    }

    context("weekdays alias") {
      fixture {
        Fixture(
          clock = weekendClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "weekdays"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("can't promote, not a weekday") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isFalse()
      }

      test("ui format is correct") {
        val windows = AllowedTimesConstraintEvaluator.toNumericTimeWindows(constraint)
        expect {
          that(windows.size).isEqualTo(1)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(IntRange(1,5).toSet(), emptySet()),
          )
        }
      }
    }

    context("weekend alias") {
      fixture {
        Fixture(
          clock = weekendClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "weekends"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("can't promote, not a weekday") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isTrue()
      }

      test("ui format is correct") {
        val windows = AllowedTimesConstraintEvaluator.toNumericTimeWindows(constraint)
        expect {
          that(windows.size).isEqualTo(1)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(IntRange(6,7).toSet(), emptySet()),
          )
        }
      }
    }

    context("environment constraint can use short days for default locale") {
      fixture {
        Fixture(
          clock = businessHoursClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "thu",
                hours = "11-16"
              ),
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
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isTrue()
      }

      test("ui format is correct") {
        val windows = AllowedTimesConstraintEvaluator.toNumericTimeWindows(constraint)
        expect {
          that(windows.size).isEqualTo(2)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(setOf(4), IntRange(11,16).toSet()),
            TimeWindowNumeric(IntRange(1,5).toSet(), IntRange(11,16).toSet())
          )
        }
      }
    }

    context("allowed-times constraint with default time zone") {
      fixture {
        Fixture(
          clock = businessHoursClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "mon-fri",
                hours = "11-16"
              )
            )
          )
        )
      }

      test("11-16 is outside of allowed times when defaulting tz to UTC") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isFalse()
      }
    }

    context("wrap around hours and days") {
      fixture {
        Fixture(
          clock = mondayClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
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
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isTrue()
      }

      test("ui format is correct") {
        val windows = AllowedTimesConstraintEvaluator.toNumericTimeWindows(constraint)
        expect {
          that(windows.size).isEqualTo(1)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(setOf(2,1,7,6), setOf(23) + IntRange(0,10).toSet()),
          )
        }
      }
    }

    context("should not wrap around") {
      fixture {
        Fixture(
          clock = mondayClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "sat-tue",
                hours = "9-11"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }

      test("not in window, hour does not wrap-around") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isFalse()
      }
    }

    context("window is validated at construction") {
      test("invalid day range") {
        expectCatching {
          TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "mon-frizzay",
                hours = "11-16"
              )
            ),
            tz = "America/Los_Angeles"
          )
        }
          .isFailure()
          .isA<IllegalArgumentException>()
      }

      test("invalid hour range") {
        expectCatching {
          TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "weekdays",
                hours = "11-161"
              )
            ),
            tz = "America/Los_Angeles"
          )
        }
          .isFailure()
          .isA<IllegalArgumentException>()
      }

      test("invalid tz") {
        expectCatching {
          TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "weekdays",
                hours = "11-16"
              )
            ),
            tz = "America/Los_Spingeles"
          )
        }
          .isFailure()
          .isA<IllegalArgumentException>()
      }
    }

    context("generating the pass state") {
      fixture {
        Fixture(
          clock = businessHoursClock,
          constraint = TimeWindowConstraint(
            windows = listOf(
              TimeWindow(
                days = "Monday-Tuesday,Thursday-Friday",
                hours = "09-16"
              )
            ),
            tz = "America/Los_Angeles"
          )
        )
      }
      test("can generate status") {
        val state = subject.generateConstraintStateSnapshot(artifact = artifact, deliveryConfig = manifest, targetEnvironment = environment, version = "me-123")
        expectThat(state)
          .and { get { type }.isEqualTo("allowed-times") }
          .and { get { status }.isEqualTo(PASS) }
          .and { get { judgedAt }.isNotNull() }
          .and { get { judgedBy }.isNotNull() }
          .and { get { attributes }.isNotNull() }
      }
    }
  }
}
