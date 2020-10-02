package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.time.MutableClock
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import java.time.Clock

abstract class NotifierRepositoryTests<T: NotifierRepository> : JUnit5Minutests {
  abstract fun factory(clock: Clock): T

  open fun T.flush() {}

  val clock = MutableClock()
  val resourceId = "ec2:cluster:test:us-west-2:keeldemo-managed"
  val application = "keeldemo"

  data class Fixture<T : NotifierRepository>(
    val subject: T
  )

  fun tests() = rootContext<Fixture<T>> {
    fixture {
      Fixture(subject = factory(clock))
    }

    after { subject.flush() }

    //todo eb: write tests here
  }
}