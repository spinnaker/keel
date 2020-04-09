package com.netflix.spinnaker.keel.retrofit

import java.lang.annotation.Documented
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy.RUNTIME
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import retrofit2.http.Url

/** Marks a retrofit service request function as a Spinnaker call. */
@Documented
@Target(FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
@Retention(RUNTIME)
annotation class SpinnakerCall(
  /**
   * A relative or absolute path, or full URL of the endpoint. This value is optional if the first
   * parameter of the method is annotated with [@Url][Url].
   *
   *
   * See [base URL][retrofit2.Retrofit.Builder.baseUrl] for details of how
   * this is resolved against a base URL to create the full endpoint URL.
   */
  val value: String = ""
)
