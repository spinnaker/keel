package com.netflix.spinnaker.hamkrest

import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.should.shouldMatch

infix fun <T> T.shouldEqual(expected: T) {
  shouldMatch(equalTo(expected))
}
