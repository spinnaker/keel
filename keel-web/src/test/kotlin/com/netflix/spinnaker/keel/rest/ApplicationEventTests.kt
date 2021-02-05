package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.KeelApplication
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import strikt.api.expectThat
import strikt.assertions.isNotEqualTo
import strikt.assertions.startsWith
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException

@SpringBootTest(
  classes = [KeelApplication::class, ThreadCapturingEventListener::class],
  webEnvironment = NONE
)
//@ContextHierarchy(
//  ContextConfiguration(classes = [KeelApplication::class]),
//  ContextConfiguration(classes = [ThreadCapturingEventListener::class])
//)
internal class ApplicationEventTests
@Autowired constructor(
  val publisher: ApplicationEventPublisher,
  val listener: ThreadCapturingEventListener
) {

  @Test
  fun `events are dispatched on a different thread`() {
    val testThread = Thread.currentThread()

    publisher.publishEvent(TestEvent(this))

    val eventThread = listener.awaitInvoked(Duration.ofMillis(500))

    expectThat(eventThread)
      .isNotEqualTo(testThread)
      .get { name }
      .startsWith("event-pool-")
  }
}

internal class TestEvent(source: Any) : ApplicationEvent(source)

@Component
internal class ThreadCapturingEventListener : ApplicationListener<TestEvent> {

  private val latch = CountDownLatch(1)
  private var invokedThread: Thread? = null

  override fun onApplicationEvent(event: TestEvent) {
    invokedThread = Thread.currentThread()
    latch.countDown()
  }

  fun awaitInvoked(duration: Duration): Thread {
    if (!latch.await(duration.toMillis(), MILLISECONDS)) {
      throw TimeoutException("No value was set within $duration")
    }
    return invokedThread ?: error("Value was set but is null")
  }
}
