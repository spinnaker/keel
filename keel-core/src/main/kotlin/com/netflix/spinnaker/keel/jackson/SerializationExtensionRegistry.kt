package com.netflix.spinnaker.keel.jackson

import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * A registry to allow customization of JSON serializers and deserializers .
 */
@Component
class SerializationExtensionRegistry {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }
  private val _deserializers: MutableMap<Class<*>, JsonDeserializer<*>> = mutableMapOf()
  private val _serializers: MutableMap<Class<*>, JsonSerializer<*>> = mutableMapOf()

  val deserializers: Map<Class<*>, JsonDeserializer<*>>
    get() = _deserializers.toMap()

  val serializers: Map<Class<*>, JsonSerializer<*>>
    get() = _serializers.toMap()

  fun <T : JsonDeserializer<*>> register(javaClass: Class<*>, deserializer: T) {
    _deserializers.putIfAbsent(javaClass, deserializer)
      ?.let {
        if (it.javaClass != deserializer.javaClass) {
          log.warn("Attempt to register deserializer ${deserializer.javaClass.name} for type ${javaClass.name}, but ${it.javaClass.name} is already registered.")
        }
      }
  }

  fun <T : JsonSerializer<*>> register(javaClass: Class<*>, serializer: T) {
    _serializers.putIfAbsent(javaClass, serializer)
      ?.let {
        if (it.javaClass != serializer.javaClass) {
          log.warn("Attempt to register serializer ${serializer.javaClass.name} for type ${javaClass.name}, but ${it.javaClass.name} is already registered.")
        }
      }
  }
}