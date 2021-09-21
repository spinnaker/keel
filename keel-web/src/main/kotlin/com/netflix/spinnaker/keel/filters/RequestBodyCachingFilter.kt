package com.netflix.spinnaker.keel.filters

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered.HIGHEST_PRECEDENCE
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component

import org.springframework.web.filter.OncePerRequestFilter
import javax.servlet.FilterChain
import javax.servlet.annotation.WebFilter
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Wraps requests with the configured URL patterns with a [BodyCachingRequestWrapper] such that the request
 * body can be read more than once through the filter chain and finally the controller.
 *
 * Primarily used to support Slack callback requests, which are form-encoded POSTs that cause the body
 * to be consumed inadvertently by request filters.
 */
@Order(value = HIGHEST_PRECEDENCE)
@Component
@WebFilter(
  filterName = "RequestBodyCachingFilter",
  urlPatterns = ["/slack/notifications/callbacks"]
)
class RequestBodyCachingFilter : OncePerRequestFilter() {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
    if (request.method == HttpMethod.POST.name) {
      log.debug("Caching request body for ${request.method} ${request.requestURI}")
      val wrapper = BodyCachingRequestWrapper(request)
      chain.doFilter(wrapper, response)
    } else {
      chain.doFilter(request, response)
    }
  }

  // for some reason, the servlet container runs the filter on requests that don't match the URL patterns
  // declared in @WebFilter above, so we need to override this function as well.
  override fun shouldNotFilter(request: HttpServletRequest): Boolean {
    return request.requestURI != "/slack/notifications/callbacks"
  }
}
