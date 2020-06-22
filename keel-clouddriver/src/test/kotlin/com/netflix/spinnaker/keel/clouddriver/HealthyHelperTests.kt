package com.netflix.spinnaker.keel.clouddriver

import com.netflix.spinnaker.keel.api.ClusterHealth
import com.netflix.spinnaker.keel.clouddriver.model.InstanceCounts
import com.netflix.spinnaker.keel.clouddriver.model.isHealthy
import com.netflix.spinnaker.keel.clouddriver.model.meetsHealthyThreshold
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expectThat
import strikt.assertions.isFalse
import strikt.assertions.isTrue

class HealthyHelperTests : JUnit5Minutests {
  class Fixture {
    val ignoreHealth = ClusterHealth(ignoreHealthForDeployments = true)
    val ignoreHealthAndSpecifyPercentage = ClusterHealth(ignoreHealthForDeployments = true, healthyPercentage = 88.0)
    val specifyPercentage = ClusterHealth(healthyPercentage = 88.0)
    val default = ClusterHealth()
  }

  fun tests() = rootContext<Fixture> {
    fixture { Fixture() }

    context("no info provided") {
      test("returns false") {
        expectThat(isHealthy(null, null)).isFalse()
      }
    }

    context("info provided") {
      test("100% and ignoring health, healthy case") {
        expectThat(
          isHealthy(ignoreHealth, InstanceCounts(100, 0, 0, 100, 0, 0))
        ).isTrue()
      }
      test("100% and ignoring health, unhealthy case") {
        expectThat(
          isHealthy(ignoreHealth, InstanceCounts(100, 0, 0, 99, 1, 0))
        ).isFalse()
      }
      test("88% and ignoring health, healthy case") {
        expectThat(
          isHealthy(ignoreHealthAndSpecifyPercentage, InstanceCounts(100, 0, 0, 90, 10, 0))
        ).isTrue()
      }
      test("88% and ignoring health, unhealthy case") {
        expectThat(
          isHealthy(ignoreHealthAndSpecifyPercentage, InstanceCounts(100, 0, 0, 87, 13, 0))
        ).isFalse()
      }
      test("100% and consider health, healthy case") {
        expectThat(
          isHealthy(default, InstanceCounts(2, 2, 0, 0, 0, 0))
        ).isTrue()
      }
      test("100% and consider health, healthy case") {
        expectThat(
          isHealthy(default, InstanceCounts(2, 1, 0, 1, 0, 0))
        ).isFalse()
      }
      test("88% and consider health, healthy case") {
        expectThat(
          isHealthy(specifyPercentage, InstanceCounts(10, 9, 1, 0, 0, 0))
        ).isTrue()
      }
      test("88% and consider health, unhealthy case") {
        expectThat(
          isHealthy(specifyPercentage, InstanceCounts(10, 8, 1, 1, 0, 0))
        ).isFalse()
      }
    }

    context("percentage tests") {
      test("88 needed 87 healthy") {
        expectThat(meetsHealthyThreshold(87.0, 100.0, 88.0)).isFalse()
      }
    }
  }
}
