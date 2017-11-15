package com.netflix.spinnaker.keel.intents.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.TextNode
import com.netflix.spinnaker.keel.intents.AvailabilityZoneSpec
import com.netflix.spinnaker.keel.intents.AvailabilityZoneSpec.automatic
import com.netflix.spinnaker.keel.intents.AvailabilityZoneSpec.manual

class AvailabilityZoneSpecSerializer : JsonSerializer<AvailabilityZoneSpec>() {
  override fun serialize(
    value: AvailabilityZoneSpec,
    gen: JsonGenerator,
    serializers: SerializerProvider
  ) {
    when (value) {
      is automatic -> gen.writeString(automatic.javaClass.simpleName)
      is manual -> value.availabilityZones.apply {
        gen.writeStartArray()
        forEach {
          gen.writeString(it)
        }
        gen.writeEndArray()
      }
    }
  }
}

class AvailabilityZoneSpecDeserializer : JsonDeserializer<AvailabilityZoneSpec>() {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): AvailabilityZoneSpec {
    val tree: TreeNode = p.readValueAsTree()
    return when (tree) {
      is TextNode -> automatic
      is ArrayNode -> manual(tree.map { it.textValue() }.toSet())
      else -> throw InvalidFormatException(
        p,
        "Expected text or array but found ${tree.javaClass.simpleName}",
        tree,
        AvailabilityZoneSpec::class.java
      )
    }
  }

}
