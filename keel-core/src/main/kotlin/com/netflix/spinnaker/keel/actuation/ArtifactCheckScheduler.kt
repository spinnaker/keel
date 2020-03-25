package com.netflix.spinnaker.keel.actuation

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

class ArtifactCheckScheduler : CoroutineScope {

  @Scheduled(fixedDelayString = "\${keel.artifact-check.frequency:PT1S}")
  fun checkArtifacts() {}

  override val coroutineContext: CoroutineContext = IO

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
