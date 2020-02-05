package com.netflix.spinnaker.keel.serialization

import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_WITH_ZONE_ID
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.cfg.MapperConfig
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.introspect.AnnotatedClass
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder
import com.fasterxml.jackson.databind.jsontype.impl.StdTypeResolverBuilder
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.Serializers
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.USE_NATIVE_TYPE_ID
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.netflix.spinnaker.keel.api.ArtifactType.deb
import com.netflix.spinnaker.keel.api.ArtifactType.docker
import com.netflix.spinnaker.keel.api.Constraint
import com.netflix.spinnaker.keel.api.DebianArtifact
import com.netflix.spinnaker.keel.api.DebianSemVerVersioningStrategy
import com.netflix.spinnaker.keel.api.DeliveryArtifact
import com.netflix.spinnaker.keel.api.DockerArtifact
import com.netflix.spinnaker.keel.api.DockerVersioningStrategy
import com.netflix.spinnaker.keel.api.TagVersionStrategy
import com.netflix.spinnaker.keel.api.UID
import com.netflix.spinnaker.keel.api.VersioningStrategy
import com.netflix.spinnaker.keel.api.VersioningStrategyDeserializer
import java.text.SimpleDateFormat
import java.util.TimeZone

/**
 * Factory method for [ObjectMapper]s configured how we like 'em.
 */
fun configuredObjectMapper(): ObjectMapper =
  ObjectMapper().configureMe()

/**
 * Factory method for [YAMLMapper]s configured how we like 'em.
 */
fun configuredYamlMapper(): YAMLMapper =
  YAMLMapper()
    .configureMe()
    .disable(USE_NATIVE_TYPE_ID)

private fun <T : ObjectMapper> T.configureMe(): T =
  apply {
    registerModule(KeelApiModule)
      .registerKotlinModule()
      .registerULIDModule()
      .registerModule(JavaTimeModule())
      .configureSaneDateTimeRepresentation()
      .disable(FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(ACCEPT_CASE_INSENSITIVE_ENUMS)
  }

/**
 * TODO: move this to its own home as it's becoming increasingly complex
 */
object KeelApiModule : SimpleModule("Keel API") {
  val types = setOf(Constraint::class.java, DeliveryArtifact::class.java)

  override fun setupModule(context: SetupContext) {
    with(context) {
      insertAnnotationIntrospector(object : NopAnnotationIntrospector() {
        override fun findTypeResolver(config: MapperConfig<*>, ac: AnnotatedClass, baseType: JavaType): TypeResolverBuilder<*>? {
          // This is the equivalent of using a @JsonTypeInfo annotation with the specified settings.
          // We don't want to transitively ship jackson-annotations, though. Sub-types need to be
          // registered programmatically.
          return if (baseType.rawClass in types) {
            StdTypeResolverBuilder()
              .init(JsonTypeInfo.Id.NAME, null)
              .inclusion(JsonTypeInfo.As.EXISTING_PROPERTY)
              .typeProperty("type")
          } else {
            super.findTypeResolver(config, ac, baseType)
          }
        }
      })

      addSerializers(object : Serializers.Base() {
        override fun findSerializer(config: SerializationConfig, type: JavaType, beanDesc: BeanDescription): JsonSerializer<*>? =
          when (type.rawClass) {
            TagVersionStrategy::class.java -> TagVersionStrategySerializer
            else -> null
          }
      })

      addDeserializers(object : Deserializers.Base() {
        override fun findEnumDeserializer(type: Class<*>, config: DeserializationConfig, beanDesc: BeanDescription): JsonDeserializer<*>? =
          when (type) {
            TagVersionStrategy::class.java -> TagVersionStrategyDeserializer
            else -> null
          }

        override fun findBeanDeserializer(type: JavaType, config: DeserializationConfig, beanDesc: BeanDescription): JsonDeserializer<*>? =
          when (type.rawClass) {
            VersioningStrategy::class.java -> VersioningStrategyDeserializer
            else -> null
          }
      })

      setMixInAnnotations(DeliveryArtifact::class.java, DeliveryArtifactMixin::class.java)

      registerSubtypes(
        NamedType(DebianSemVerVersioningStrategy::class.java, deb.name),
        NamedType(DockerVersioningStrategy::class.java, docker.name)
      )

      registerSubtypes(
        NamedType(DebianArtifact::class.java, deb.name),
        NamedType(DockerArtifact::class.java, docker.name)
      )
    }
  }
}

private interface DeliveryArtifactMixin {
  @get:JsonProperty(access = WRITE_ONLY)
  val deliveryConfigName: String

  @get:JsonProperty(access = WRITE_ONLY)
  val versioningStrategy: VersioningStrategy
}

object TagVersionStrategySerializer : StdSerializer<TagVersionStrategy>(TagVersionStrategy::class.java) {
  override fun serialize(value: TagVersionStrategy, gen: JsonGenerator, provider: SerializerProvider) {
    gen.writeString(value.friendlyName)
  }
}

object TagVersionStrategyDeserializer : StdDeserializer<TagVersionStrategy>(TagVersionStrategy::class.java) {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): TagVersionStrategy {
    val value = p.text
    return TagVersionStrategy
      .values()
      .find { it.friendlyName == value }
      ?: throw ctxt.weirdStringException(value, TagVersionStrategy::class.java, "not one of the values accepted for Enum class: %s")
  }
}

private fun ObjectMapper.registerULIDModule(): ObjectMapper =
  registerModule(SimpleModule("ULID").apply {
    addSerializer(UID::class.java, ToStringSerializer())
    addDeserializer(UID::class.java, ULIDDeserializer())
  })

private fun ObjectMapper.configureSaneDateTimeRepresentation(): ObjectMapper =
  enable(WRITE_DATES_AS_TIMESTAMPS)
    .setSerializationInclusion(NON_NULL)
    .enable(WRITE_DATES_WITH_ZONE_ID)
    .enable(WRITE_DATE_KEYS_AS_TIMESTAMPS)
    .disable(WRITE_DURATIONS_AS_TIMESTAMPS)
    .apply {
      dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ").apply {
        timeZone = TimeZone.getDefault()
      }
    }
