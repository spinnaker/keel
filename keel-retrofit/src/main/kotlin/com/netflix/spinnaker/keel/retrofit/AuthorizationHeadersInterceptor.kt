package com.netflix.spinnaker.keel.retrofit

import com.netflix.spinnaker.kork.common.Header
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Response
import org.slf4j.LoggerFactory

/**
 * Okhttp3 interceptor that adds the X-SPINNAKER-USER and X-SPINNAKER-ACCOUNTS headers to enable authorization
 * with downstream Spinnaker services.
 */
class AuthorizationHeadersInterceptor : Interceptor {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun intercept(chain: Chain): Response {
    var request = chain.request()
    val headers = SpinnakerCallContexts.get()[chain.call().request()]

    if (headers != null) {
      headers[Header.USER.header]?.also { xSpinnakerUser ->
        log.debug("Adding X-SPINNAKER-USER: $xSpinnakerUser to ${request.method} ${request.url}")
        request = request
          .newBuilder()
          .addHeader(Header.USER.header, xSpinnakerUser)
          .build()
      }
      headers[Header.ACCOUNTS.header]?.also { xSpinnakerAccounts ->
        log.debug("Adding X-SPINNAKER-ACCOUNTS: $xSpinnakerAccounts to ${request.method} ${request.url}")
        request = request
          .newBuilder()
          .addHeader(Header.ACCOUNTS.header, xSpinnakerAccounts)
          .build()
      }
    }

    return chain.proceed(request)
  }
}
