package com.netflix.spinnaker.keel.test

import com.netflix.spinnaker.keel.pause.ActuationPauser
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.CombinedRepository
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.PausedRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryArtifactRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryDeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryPausedRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryResourceRepository
import io.mockk.mockk
import java.time.Clock
import org.springframework.context.ApplicationEventPublisher

class InMemoryCombinedRepository(
  override val clock: Clock = Clock.systemUTC(),
  override val deliveryConfigRepository: InMemoryDeliveryConfigRepository = InMemoryDeliveryConfigRepository(clock),
  override val artifactRepository: InMemoryArtifactRepository = InMemoryArtifactRepository(clock),
  override val resourceRepository: InMemoryResourceRepository = InMemoryResourceRepository(clock),
  val pausedRepository: InMemoryPausedRepository = InMemoryPausedRepository(clock),
  private val eventPublisher: ApplicationEventPublisher = mockk(relaxed = true),
  override val actuationPauser: ActuationPauser = ActuationPauser(resourceRepository, pausedRepository, eventPublisher, clock)
) : CombinedRepository(
  deliveryConfigRepository,
  artifactRepository,
  resourceRepository,
  clock,
  eventPublisher,
  actuationPauser
) {
  fun dropAll() {
    deliveryConfigRepository.dropAll()
    artifactRepository.dropAll()
    resourceRepository.dropAll()
    pausedRepository.flush()
  }
}

/**
 * A util for generating a combined repository with in memory implementations for tests
 */
fun combinedInMemoryRepository(
  clock: Clock = Clock.systemUTC(),
  deliveryConfigRepository: InMemoryDeliveryConfigRepository = InMemoryDeliveryConfigRepository(clock),
  artifactRepository: InMemoryArtifactRepository = InMemoryArtifactRepository(clock),
  resourceRepository: InMemoryResourceRepository = InMemoryResourceRepository(clock),
  pausedRepository: InMemoryPausedRepository = InMemoryPausedRepository(clock)
): InMemoryCombinedRepository =
  InMemoryCombinedRepository(clock, deliveryConfigRepository, artifactRepository, resourceRepository, pausedRepository)

fun combinedMockRepository(
  deliveryConfigRepository: DeliveryConfigRepository = mockk(relaxed = true),
  artifactRepository: ArtifactRepository = mockk(relaxed = true),
  resourceRepository: ResourceRepository = mockk(relaxed = true),
  pausedRepository: PausedRepository = mockk(relaxed = true),
  clock: Clock = Clock.systemUTC(),
  publisher: ApplicationEventPublisher = mockk(relaxed = true),
  actuationPauser: ActuationPauser = ActuationPauser(resourceRepository, pausedRepository, publisher, clock)
): CombinedRepository =
  CombinedRepository(deliveryConfigRepository, artifactRepository, resourceRepository, clock, publisher, actuationPauser)
