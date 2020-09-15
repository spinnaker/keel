/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.core

import com.netflix.rocket.semver.shaded.DebianVersionComparator
import com.netflix.spinnaker.keel.api.artifacts.SortType.INCREASING
import com.netflix.spinnaker.keel.api.artifacts.SortType.SEMVER
import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy
import com.netflix.spinnaker.keel.artifacts.NetflixSemVerVersioningStrategy
import com.netflix.spinnaker.keel.exceptions.InvalidRegexException
import net.swiftzer.semver.SemVer
import org.slf4j.LoggerFactory
import org.springframework.util.comparator.NullSafeComparator

/**
 * Comparator that supports all the tag options for docker containers
 */
class TagComparator(
  private val strategy: TagVersionStrategy,
  private val customRegex: String? = null
) : Comparator<String> {

  private val semverComparator = NullSafeComparator(SEMVER_COMPARATOR, false)
  private val increasingComparator = NullSafeComparator(INCREASING_COMPARATOR, false)

  override fun compare(o1: String, o2: String): Int {
    val i1 = parseWithRegex(o1, strategy, customRegex)
    val i2 = parseWithRegex(o2, strategy, customRegex)
    return when (strategy.sortType) {
      SEMVER -> semverComparator.compare(parseSemver(i1), parseSemver(i2))
      INCREASING -> increasingComparator.compare(i1?.toLongOrNull(), i2?.toLongOrNull())
    }
  }

  companion object {
    private val log by lazy { LoggerFactory.getLogger(this::class.java) }

    fun parseWithRegex(input: String, strategy: TagVersionStrategy, customRegex: String?): String? {
      val regex = Regex(customRegex ?: strategy.regex)
      val result = regex.find(input) ?: return null
      return when (result.groupValues.size) {
        2 -> result.groupValues[1]
        1 -> {
          log.warn("Regex (${customRegex ?: strategy.regex}) produced zero capture groups on tag $input")
          null
        }
        else -> {
          throw InvalidRegexException(customRegex ?: strategy.regex, input)
        }
      }
    }
  }

  /**
   * Trims a leading "v" off of the semver if present
   */
  private fun parseSemver(input: String?): SemVer? {
    input ?: return null
    return try {
      SemVer.parse(input.removePrefix("v"))
    } catch (e: IllegalArgumentException) {
      null
    }
  }
}

val SEMVER_COMPARATOR: Comparator<SemVer> = Comparator { a, b ->
  b.compareTo(a)
}

val INCREASING_COMPARATOR: Comparator<Long> = Comparator { a, b ->
  b.compareTo(a)
}

// descending by default
val NETFLIX_SEMVER_COMPARATOR: Comparator<String> = object : Comparator<String> {
  override fun compare(s1: String, s2: String) =
    debComparator.compare(s2.toVersion(), s1.toVersion())

  private val debComparator = NullSafeComparator(DebianVersionComparator(), true)

  private fun String.toVersion(): String? = run {
    val appVersion = NetflixSemVerVersioningStrategy.extractVersion(this)
    if (appVersion == null) {
      log.warn("Unparseable artifact version \"{}\" encountered. Sorting results will be unpredictable.", this)
      null
    } else {
      appVersion
    }
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
