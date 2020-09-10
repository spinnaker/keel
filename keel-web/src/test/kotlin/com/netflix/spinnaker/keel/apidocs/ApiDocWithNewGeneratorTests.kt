package com.netflix.spinnaker.keel.apidocs

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.netflix.spinnaker.keel.api.Constraint
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.VersioningStrategy
import com.netflix.spinnaker.keel.api.constraints.ConstraintStateAttributes
import com.netflix.spinnaker.keel.api.constraints.DefaultConstraintAttributes
import com.netflix.spinnaker.keel.api.ec2.ArtifactImageProvider
import com.netflix.spinnaker.keel.api.ec2.EC2_APPLICATION_LOAD_BALANCER_V1
import com.netflix.spinnaker.keel.api.ec2.EC2_APPLICATION_LOAD_BALANCER_V1_1
import com.netflix.spinnaker.keel.api.ec2.EC2_CLASSIC_LOAD_BALANCER_V1
import com.netflix.spinnaker.keel.api.ec2.EC2_CLUSTER_V1
import com.netflix.spinnaker.keel.api.ec2.EC2_SECURITY_GROUP_V1
import com.netflix.spinnaker.keel.api.ec2.JenkinsImageProvider
import com.netflix.spinnaker.keel.api.ec2.ReferenceArtifactImageProvider
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import com.netflix.spinnaker.keel.api.support.extensionsOf
import com.netflix.spinnaker.keel.api.support.register
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.artifacts.DockerVersioningStrategy
import com.netflix.spinnaker.keel.artifacts.NetflixSemVerVersioningStrategy
import com.netflix.spinnaker.keel.artifacts.NpmArtifact
import com.netflix.spinnaker.keel.bakery.api.ImageExistsConstraint
import com.netflix.spinnaker.keel.constraints.CanaryConstraintAttributes
import com.netflix.spinnaker.keel.constraints.PipelineConstraintStateAttributes
import com.netflix.spinnaker.keel.core.api.ArtifactUsedConstraint
import com.netflix.spinnaker.keel.core.api.CanaryConstraint
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.ManualJudgementConstraint
import com.netflix.spinnaker.keel.core.api.PipelineConstraint
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.SubmittedResource
import com.netflix.spinnaker.keel.core.api.TimeWindowConstraint
import com.netflix.spinnaker.keel.docker.DigestProvider
import com.netflix.spinnaker.keel.docker.ReferenceProvider
import com.netflix.spinnaker.keel.docker.VersionedTagProvider
import com.netflix.spinnaker.keel.ec2.jackson.KeelEc2ApiModule
import com.netflix.spinnaker.keel.extensions.DefaultExtensionRegistry
import com.netflix.spinnaker.keel.jackson.KeelApiModule
import com.netflix.spinnaker.keel.schema.Generator
import com.netflix.spinnaker.keel.schema.generateSchema
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.keel.titus.TITUS_CLUSTER_V1
import com.netflix.spinnaker.keel.titus.jackson.KeelTitusApiModule
import dev.minutest.experimental.SKIP
import dev.minutest.experimental.minus
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import io.swagger.v3.core.util.RefUtils.constructRef
import kotlin.reflect.KClass
import strikt.api.Assertion
import strikt.api.expectThat
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.doesNotContain
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isTrue
import strikt.jackson.at
import strikt.jackson.booleanValue
import strikt.jackson.findValuesAsText
import strikt.jackson.has
import strikt.jackson.isArray
import strikt.jackson.isMissing
import strikt.jackson.isObject
import strikt.jackson.isTextual
import strikt.jackson.path
import strikt.jackson.textValue
import strikt.jackson.textValues

class ApiDocWithNewGeneratorTests : JUnit5Minutests {

  val mapper = configuredObjectMapper()
  val extensionRegistry: ExtensionRegistry = DefaultExtensionRegistry(listOf(mapper))
  val generator = Generator(extensionRegistry)

  val resourceKinds
    get() = extensionRegistry.extensionsOf<ResourceSpec>().keys

  val resourceSpecTypes
    get() = extensionRegistry.extensionsOf<ResourceSpec>().values.toList()

  val constraintTypes
    get() = extensionRegistry.extensionsOf<Constraint>().values.toList()

  val imageProviderTypes = listOf(
    ArtifactImageProvider::class,
    ReferenceArtifactImageProvider::class,
    JenkinsImageProvider::class
  )

  val containerProviderTypes = listOf(
    ReferenceProvider::class,
    DigestProvider::class,
    VersionedTagProvider::class
  )

  val artifactTypes
    get() = extensionRegistry.extensionsOf<DeliveryArtifact>().values.toList()

  fun tests() = rootContext<Assertion.Builder<JsonNode>> {
    fixture {
      with(mapper) {
        registerModule(KeelApiModule)
        registerModule(KeelEc2ApiModule)
        registerModule(KeelTitusApiModule)
      }

      with(extensionRegistry) {
        listOf(EC2_CLUSTER_V1, EC2_SECURITY_GROUP_V1, EC2_CLASSIC_LOAD_BALANCER_V1, EC2_APPLICATION_LOAD_BALANCER_V1, EC2_APPLICATION_LOAD_BALANCER_V1_1, TITUS_CLUSTER_V1).forEach { (kind, specClass) ->
          register<ResourceSpec>(specClass, kind.toString())
        }
        register<DeliveryArtifact>(DebianArtifact::class.java, "deb")
        register<DeliveryArtifact>(DockerArtifact::class.java, "docker")
        register<DeliveryArtifact>(NpmArtifact::class.java, "npm")
        register<VersioningStrategy>(NetflixSemVerVersioningStrategy::class.java, "deb")
        register<VersioningStrategy>(DockerVersioningStrategy::class.java, "docker")
        register<VersioningStrategy>(NetflixSemVerVersioningStrategy::class.java, "npm")
        register<Constraint>(ImageExistsConstraint::class.java, "bake")
        register<Constraint>(TimeWindowConstraint::class.java, "allowed-times")
        register<Constraint>(ArtifactUsedConstraint::class.java, "artifact-used")
        register<Constraint>(CanaryConstraint::class.java, "canary")
        register<Constraint>(DependsOnConstraint::class.java, "depends-on")
        register<Constraint>(ManualJudgementConstraint::class.java, "manual-judgement")
        register<Constraint>(PipelineConstraint::class.java, "pipeline")
        register<ConstraintStateAttributes>(CanaryConstraintAttributes::class.java, "canary")
        register<ConstraintStateAttributes>(DefaultConstraintAttributes::class.java, "manual-judgement")
        register<ConstraintStateAttributes>(PipelineConstraintStateAttributes::class.java, "pipeline")
      }

      val api = generator.generateSchema<SubmittedDeliveryConfig>()
        .also {
          jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .writeValueAsString(it)
            .also(::println)
        }
        .let { jacksonObjectMapper().valueToTree<JsonNode>(it) }
      expectThat(api).describedAs("API Docs response")
    }

    test("Does not contain a schema for ResourceKind") {
      at("/\$defs/ResourceKind")
        .isMissing()
    }

    test("Resource is defined as one of the possible resource sub-types") {
      at("/\$defs/SubmittedResource/discriminator/mapping")
        .isObject()
        .and {
          extensionRegistry.extensionsOf<ResourceSpec>().forEach { (kind, type) ->
            has(kind)
            get { get(kind).textValue() }.isEqualTo("#/\$defs/${type.simpleName}")
          }
        }
    }

    resourceSpecTypes
      .map(Class<*>::getSimpleName)
      .forEach { specSubType ->
        test("contains a parameterized version of schema for SubmittedResource with a spec of $specSubType") {
          at("/\$defs/${specSubType}SubmittedResource/oneOf/1/properties")
            .isObject()
            .and {
              path("kind").isObject().path("type").textValue().isEqualTo("string")
              path("metadata").isObject().path("type").textValue().isEqualTo("object")
              path("spec").isObject().path("\$ref").textValue().isEqualTo("#/\$defs/${specSubType}")
            }
        }
      }

    resourceSpecTypes
      .map(Class<*>::getSimpleName)
      .forEach { type ->
        test("spec property of Resource subtype $type is required") {
          at("/\$defs/${type}Resource/oneOf/1/required")
            .isArray()
            .textValues()
            .containsExactly("spec")
        }

        test("ResourceSpec sub-type $type has its own schema") {
          at("/\$defs/$type")
            .isObject()
        }
      }

    test("contains a schema for Constraint with all sub-types") {
      at("/\$defs/Constraint")
        .isObject()
        .path("oneOf")
        .isArray()
        .findValuesAsText("\$ref")
        .containsExactlyInAnyOrder(
          "#/\$defs/CanaryConstraint",
          "#/\$defs/DependsOnConstraint",
          "#/\$defs/ManualJudgementConstraint",
          "#/\$defs/PipelineConstraint",
          "#/\$defs/TimeWindowConstraint",
          "#/\$defs/ArtifactUsedConstraint",
          "#/\$defs/ImageExistsConstraint"
        )
    }

    constraintTypes
      .map(Class<*>::getSimpleName)
      .forEach { type ->
        test("Constraint sub-type $type has its own schema") {
          at("/\$defs/$type")
            .isObject()
        }
      }

    test("contains a schema for DeliveryArtifact with all sub-types") {
      at("/\$defs/DeliveryArtifact")
        .isObject()
        .path("oneOf")
        .isArray()
        .findValuesAsText("\$ref")
        .containsExactlyInAnyOrder(artifactTypes.map { "#/\$defs/${it.simpleName}" })
    }

    sequenceOf(
      DebianArtifact::class,
      DockerArtifact::class
    )
      .map(KClass<*>::simpleName)
      .forEach { type ->
        test("DeliveryArtifact sub-type $type has its own schema") {
          at("/\$defs/$type")
            .isObject()
        }
      }

    test("schema for a sealed class is oneOf the sub-types") {
      at("/\$defs/ImageProvider")
        .isObject()
        .has("oneOf")
        .path("oneOf")
        .isArray()
        .findValuesAsText("\$ref")
        .containsExactlyInAnyOrder(
          "#/\$defs/ArtifactImageProvider",
          "#/\$defs/JenkinsImageProvider",
          "#/\$defs/ReferenceArtifactImageProvider"
        )
    }

    imageProviderTypes.map(KClass<*>::simpleName)
      .forEach { type ->
        test("ImageProvider sub-type $type has its own schema") {
          at("/\$defs/$type")
            .isObject()
        }
      }

    test("schema for ImageProvider is oneOf the sub-types") {
      at("/\$defs/ImageProvider")
        .isObject()
        .has("oneOf")
        .path("oneOf")
        .isArray()
        .findValuesAsText("\$ref")
        .containsExactlyInAnyOrder(
          imageProviderTypes.map { "#/\$defs/${it.simpleName}" }
        )
    }

    containerProviderTypes.map(KClass<*>::simpleName)
      .forEach { type ->
        test("ContainerProvider sub-type $type has its own schema") {
          at("/\$defs/$type")
            .isObject()
        }
      }

    test("schema for ContainerProvider is oneOf the sub-types") {
      at("/\$defs/ContainerProvider")
        .isObject()
        .has("oneOf")
        .path("oneOf")
        .isArray()
        .findValuesAsText("\$ref")
        .containsExactlyInAnyOrder(
          containerProviderTypes.map { "#/\$defs/${it.simpleName}" }
        )
    }

    test("schema for DeliveryArtifact has a discriminator") {
      at("/\$defs/DeliveryArtifact")
        .isObject()
        .path("discriminator")
        .and {
          path("propertyName").textValue().isEqualTo("type")
        }
        .path("mapping")
        .and {
          path("deb").textValue().isEqualTo("#/\$defs/DebianArtifact")
          path("docker").textValue().isEqualTo("#/\$defs/DockerArtifact")
        }
    }

    test("data class parameters without default values are required") {
      at("/\$defs/SubmittedResource/required")
        .isArray()
        .textValues()
        .contains("kind")
    }

    test("data class parameters with default values are not required") {
      at("/\$defs/SubmittedResource/required")
        .isArray()
        .textValues()
        .doesNotContain("metadata")
    }

    test("nullable data class parameters without default values are not required") {
      at("/\$defs/SecurityGroupSpec/required")
        .isArray()
        .textValues()
        .doesNotContain("description")
    }

    test("prefers @JsonCreator properties to default constructor") {
      at("/\$defs/ClusterSpec/required")
        .isArray()
        .textValues()
        .containsExactlyInAnyOrder("imageProvider", "moniker")
    }

    test("duration properties are duration format strings") {
      at("/\$defs/RedBlack/properties/delayBeforeDisable")
        .and {
          path("type").textValue().isEqualTo("string")
          path("format").textValue().isEqualTo("duration")
          path("properties").isMissing()
        }
    }

    test("duration properties are duration format strings") {
      at("/\$defs/ApplicationLoadBalancerSpec/properties/idleTimeout")
        .and {
          path("type").textValue().isEqualTo("string")
          path("format").textValue().isEqualTo("duration")
          path("properties").isMissing()
        }
    }

    test("non-nullable properties are marked as non-nullable in the schema") {
      at("/\$defs/Moniker/properties/app/nullable")
        .booleanValue()
        .isFalse()
    }

    test("a class annotated with @Description can have a description") {
      at("/\$defs/SubmittedDeliveryConfig/description")
        .isTextual()
    }

    test("annotated class description is inherited") {
      at("/\$defs/ClusterSpecSubmittedResource/description")
        .isTextual()
    }

    test("a property annotated with @Description can have a description") {
      at("/\$defs/SubmittedDeliveryConfig/properties/serviceAccount/description")
        .isTextual()
    }

    test("annotated property description is inherited") {
      at("/\$defs/ClusterSpecSubmittedResource/properties/spec/description")
        .isTextual()
    }

    test("IngressPorts are either an enum or an object") {
      at("/\$defs/IngressPorts/oneOf")
        .isArray()
        .findValuesAsText("\$ref")
        .containsExactlyInAnyOrder("#/\$defs/AllPorts", "#/\$defs/PortRange")
      at("/\$defs/AllPorts")
        .and {
          path("type").textValue().isEqualTo("string")
          path("enum").isArray().textValues().containsExactly("ALL")
        }
    }

    resourceSpecTypes
      .filter { Locatable::class.java.isAssignableFrom(it) }
      .map(Class<*>::getSimpleName)
      .forEach { locatableType ->
        test("locations property of $locatableType is optional") {
          at("/\$defs/$locatableType/required")
            .isArray()
            .doesNotContain("locations")
        }
      }

    test("property with type Map<String, Any?> does not restrict the value type to object") {
      at("/\$defs/SubmittedResource/properties/metadata")
        .not()
        .has("additionalProperties")
    }
  }
}
