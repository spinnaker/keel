package com.netflix.spinnaker.keel.jackson

import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.deser.std.StdNodeBasedDeserializer

/**
 * Base class for deserializing a polymorphic type by looking at the fields present in the JSON to
 * identify the sub-type. This means no type field is necessary in the JSON.
 *
 * Extend this class and implement [identifySubType] then put
 * `@JsonDeserialize(using = MyDeserializer::class)` on the base class and
 * `@JsonDeserialize(using = JsonDeserializer.None::class)` on _all_ the sub-types.
 */
abstract class PropertyNamePolymorphicDeserializer<T>(clazz: Class<T>) : StdNodeBasedDeserializer<T>(clazz) {

  override fun convert(root: JsonNode, context: DeserializationContext): T {
    val fieldNames = root.fieldNames().asSequence().toList()
    val subType = identifySubType(root, context, fieldNames)
    return context.parser.codec.treeToValue(root, subType)
  }

  protected open fun identifySubType(
    root: JsonNode,
    context: DeserializationContext,
    fieldNames: Collection<String>
  ): Class<out T> =
    throw IllegalArgumentException("Cannot identify subtype of ${handledType()} based on these field names: $fieldNames")
}
