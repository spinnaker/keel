package com.netflix.spinnaker.keel.filters

import org.springframework.util.StreamUtils
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import javax.servlet.ReadListener
import javax.servlet.ServletInputStream
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper


/**
 * Allows the request body to be consumed multiple times to accommodate for Slack POST requests which
 * are encoded as application/x-www-form-urlencoded and whose body ends up being consumed before reaching
 * the controller by various request filters in the chain. The filters read the body of the request when
 * trying to read parameters, which in turn causes the [InputStream] associated with the request to be
 * consumed (apparently in accordance with the Servlet spec), leading the body to be empty by the time it
 * reaches our controller.
 *
 * Based on https://www.baeldung.com/spring-reading-httpservletrequest-multiple-times
 */
class BodyCachingRequestWrapper(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
  private val cachedBody: ByteArray = StreamUtils.copyToByteArray(request.inputStream)

  override fun getInputStream(): ServletInputStream {
    return CachedBodyServletInputStream(cachedBody)
  }

  override fun getReader(): BufferedReader {
    val byteArrayInputStream = ByteArrayInputStream(cachedBody)
    return BufferedReader(InputStreamReader(byteArrayInputStream))
  }

  class CachedBodyServletInputStream(cachedBody: ByteArray) : ServletInputStream() {
    private val cachedBodyInputStream: InputStream = ByteArrayInputStream(cachedBody)

    override fun isFinished(): Boolean {
      return cachedBodyInputStream.available() == 0
    }

    override fun isReady(): Boolean {
      return true
    }

    override fun setReadListener(readListener: ReadListener) {
      throw UnsupportedOperationException()
    }

    override fun read(): Int {
      return cachedBodyInputStream.read()
    }
  }
}