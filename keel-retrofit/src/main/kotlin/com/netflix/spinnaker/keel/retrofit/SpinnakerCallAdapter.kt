package com.netflix.spinnaker.keel.retrofit

import com.netflix.spinnaker.kork.common.Header.ACCOUNTS
import com.netflix.spinnaker.kork.common.Header.USER
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Request
import org.slf4j.MDC
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.CallAdapter.Factory
import retrofit2.Callback
import retrofit2.Retrofit

object SpinnakerCallContexts : AtomicReference<MutableMap<Request, Map<String, String>>>(mutableMapOf())

class SpinnakerCallWrapper<T>(
  private val call: Call<T>
) : Call<T> by call {

  override fun enqueue(callback: Callback<T>) {
    SpinnakerCallContexts.getAndUpdate {
      it[request()] = mapOf(
        USER.header to MDC.get(USER.header),
        ACCOUNTS.header to MDC.get(ACCOUNTS.header)
      )
      it
    }
    // TODO: add callback to remove entry from map
    call.enqueue(callback)
  }

  override fun clone(): SpinnakerCallWrapper<T> = SpinnakerCallWrapper(call.clone())
}

class SpinnakerCallAdapter<T : Any>(
  private val responseType: Type
) : CallAdapter<T, SpinnakerCallWrapper<T>> {
  override fun responseType(): Type = responseType
  override fun adapt(call: Call<T>): SpinnakerCallWrapper<T> = SpinnakerCallWrapper(call)
  // private inline fun <reified T> getType() = T::class.java
}

object SpinnakerCallAdapterFactory : Factory() {
  override fun get(returnType: Type, annotations: Array<Annotation>, retrofit: Retrofit): CallAdapter<*, *>? {
    if (annotations.any { it is SpinnakerCall } && returnType is ParameterizedType) {
      return SpinnakerCallAdapter<Any>(returnType.actualTypeArguments.first())
    }
    return null
  }
}
