package com.netflix.spinnaker.keel.model

import com.netflix.spinnaker.keel.processing.randomBytes
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import strikt.api.expect
import strikt.assertions.isEqualTo
import strikt.assertions.isNotEqualTo
import java.util.*

internal class AssetTests {
  @TestFactory
  fun `fingerprints match if specs are the same`() =
    listOf(
      randomBytes(),
      ByteArray(1)
    )
      .map { bytes ->
        Pair(asset(bytes), asset(bytes))
      }
      .map { (asset1, asset2) ->
        dynamicTest("fingerprints of 2 assets with spec ${asset1.spec.base64} match") {
          expect(asset1.fingerprint).isEqualTo(asset2.fingerprint)
        }
      }

  @TestFactory
  fun `fingerprints do not match if spec differs`() =
    listOf(
      Pair(randomBytes(), randomBytes()),
      Pair(ByteArray(2), ByteArray(1))
    )
      .map { (bytes1, bytes2) ->
        Pair(asset(bytes1), asset(bytes2))
      }
      .map { (asset1, asset2) ->
        dynamicTest("fingerprints of 2 assets with specs ${asset1.spec.base64} and ${asset2.spec.base64} do not match") {
          expect(asset1.fingerprint).isNotEqualTo(asset2.fingerprint)
        }
      }

  private fun asset(spec: ByteArray): Asset =
    Asset(
      id = AssetId("SecurityGroup:aws:prod:us-west-2:keel"),
      kind = "SecurityGroup",
      spec = spec
    )

  private val ByteArray.base64: String
    get() = Base64.getEncoder().encodeToString(this)
}
