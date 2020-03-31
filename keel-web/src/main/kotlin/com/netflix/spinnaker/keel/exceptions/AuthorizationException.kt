package com.netflix.spinnaker.keel.exceptions

import org.springframework.security.access.AccessDeniedException

class AuthorizationException(
  val user: String,
  val serviceAccount: String
) : AccessDeniedException("User $user does not have access to service account $serviceAccount.")
