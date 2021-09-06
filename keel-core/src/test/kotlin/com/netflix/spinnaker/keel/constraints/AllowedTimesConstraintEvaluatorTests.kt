package com.netflix.spinnaker.keel.constraints

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import com.netflix.spinnaker.keel.api.constraints.ConstraintRepository
import com.netflix.spinnaker.keel.api.constraints.ConstraintState
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.FAIL
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.PASS
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.PENDING
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.core.api.ALLOWED_TIMES_CONSTRAINT_TYPE
import com.netflix.spinnaker.keel.core.api.TimeWindow
import com.netflix.spinnaker.keel.core.api.TimeWindowConstraint
import com.netflix.spinnaker.keel.core.api.TimeWindowNumeric
import com.netflix.spinnaker.keel.core.api.windowsNumeric
import com.netflix.spinnaker.keel.core.api.zone
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.kork.dynamicconfig.DynamicConfigService
import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import strikt.api.expect
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import strikt.mockk.captured
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal class AllowedTimesConstraintEvaluatorTests : JUnit5Minutests {
  companion object {
    // In America/Los_Angeles, this was 1pm on a Thursday
    val businessHoursClock: Clock = Clock.fixed(Instant.parse("2019-10-31T20:00:00Z"), ZoneId.of("UTC"))
    // In America/Los_Angeles, this was 1pm on a Saturday
    val weekendClock: Clock = Clock.fixed(Instant.parse("2019-11-02T20:00:00Z"), ZoneId.of("UTC"))
    // In America/Los_Angeles, this was 6am on a Monday
    val mondayClock: Clock = Clock.fixed(Instant.parse("2019-10-28T13:00:00Z"), ZoneId.of("UTC"))

    val mutableClock = MutableClock()
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
    val version = "1.1"
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

    val pendingState = ConstraintState(
      deliveryConfigName = manifest.name,
      environmentName = environment.name,
      artifactVersion = version,
      artifactReference = artifact.reference,
      type = constraint.type,
      status = PENDING,
      judgedBy = "Spinnaker",
      judgedAt = clock.instant()
    )
    val passState = pendingState.copy(status = PASS)
    val failState = pendingState.copy(status = FAIL)

    val repository: ConstraintRepository = mockk(relaxed = true) {
      every { getConstraintState(manifest.name, environment.name, any(), ALLOWED_TIMES_CONSTRAINT_TYPE, any()) } returns pendingState
    }

    val artifactRepository: ArtifactRepository = mockk()

    val subject = AllowedTimesConstraintEvaluator(clock, dynamicConfigService, mockk(), repository, artifactRepository)
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
      val newState = slot<ConstraintState>()
      verify(exactly = 1) { repository.storeConstraintState(capture(newState)) }
      expectThat(newState.captured.status).isEqualTo(PASS)
    }

    test("saved status changes from pending to pass") {
      subject.canPromote(artifact, "1.1", manifest, environment)
      val newState = slot<ConstraintState>()
      verify(exactly = 1) { repository.storeConstraintState(capture(newState)) }
      expectThat(newState.captured.status).isEqualTo(PASS)
    }

    context("with a limit on deployments") {
      deriveFixture {
        copy(constraint = constraint.copy(maxDeploysPerWindow = 2))
      }

      context("we have not approved any versions yet in this window") {
        val start = slot<Instant>()
        val end = slot<Instant>()

        before {
          every {
            artifactRepository.versionsApprovedBetween(manifest, environment.name, capture(start), capture(end))
          } returns 0
        }

        test("we can promote") {
          expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
            .isTrue()
        }

        test("we used the correct boundaries for the time window") {
          expect {
            that(start).captured.get { atZone(constraint.zone).toLocalTime() } isEqualTo LocalTime.of(9, 0)
            that(end).captured.get { atZone(constraint.zone).toLocalTime() } isEqualTo LocalTime.of(15, 59, 59)
          }
        }
      }

      context("we have already approved several versions") {
        before {
          every {
            artifactRepository.versionsApprovedBetween(manifest, environment.name, any(), any())
          } returns 2
        }

        test("we cannot promote") {
          expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
            .isFalse()
        }
      }
    }

    context("with mutable clock") {
      fixture {
        Fixture(
          clock = mutableClock,
          constraint = TimeWindowConstraint(
            windows = listOf(),
            tz = "America/Los_Angeles"
          )
        )
      }

      before {
        every { repository.getConstraintState(manifest.name, environment.name, any(), ALLOWED_TIMES_CONSTRAINT_TYPE, any()) } returns failState
      }

      test("time is updated to last judged time") {
        mutableClock.incrementBy(Duration.ofMinutes(1))
        val currentTime = mutableClock.instant()
        subject.canPromote(artifact, "1.1", manifest, environment)
        val newState = slot<ConstraintState>()
        verify(exactly = 1) { repository.storeConstraintState(capture(newState)) }
        expectThat(newState.captured.status).isEqualTo(FAIL)
        expectThat(newState.captured.judgedAt).isEqualTo(currentTime)
      }
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
        val windows = constraint.windowsNumeric
        expect {
          that(windows.size).isEqualTo(2)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(setOf(1), (11..15).toSet()),
            TimeWindowNumeric((3..5).toSet(), setOf(13))
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

      before {
        every { repository.getConstraintState(manifest.name, environment.name, any(), ALLOWED_TIMES_CONSTRAINT_TYPE, any()) } returns passState
      }

      test("can't promote, out of window") {
        expectThat(subject.canPromote(artifact, "1.1", manifest, environment))
          .isFalse()
      }

      test("saved status flips from pass to fail") {
        subject.canPromote(artifact, "1.1", manifest, environment)
        val newState = slot<ConstraintState>()
        verify(exactly = 1) { repository.storeConstraintState(capture(newState)) }
        expectThat(newState.captured.status).isEqualTo(FAIL)
      }

      test("ui format is correct") {
        val windows = constraint.windowsNumeric
        expect {
          that(windows.size).isEqualTo(1)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric((1..5).toSet(), (11..15).toSet()),
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
        val windows = constraint.windowsNumeric
        expect {
          that(windows.size).isEqualTo(1)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric((1..5).toSet(), emptySet()),
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
        val windows = constraint.windowsNumeric
        expect {
          that(windows.size).isEqualTo(1)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric((6..7).toSet(), emptySet()),
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
        val windows = constraint.windowsNumeric
        expect {
          that(windows.size).isEqualTo(2)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(setOf(4), (11..15).toSet()),
            TimeWindowNumeric((1..5).toSet(), (11..15).toSet())
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
        val windows = constraint.windowsNumeric
        expect {
          that(windows.size).isEqualTo(1)
          that(windows).containsExactlyInAnyOrder(
            TimeWindowNumeric(setOf(2, 1, 7, 6), setOf(23) + (0..9).toSet()),
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
  }
}
