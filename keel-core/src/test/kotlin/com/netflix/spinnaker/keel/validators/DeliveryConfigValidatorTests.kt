package com.netflix.spinnaker.keel.validators

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.SubmittedEnvironment
import com.netflix.spinnaker.keel.core.api.SubmittedResource
import com.netflix.spinnaker.keel.exceptions.DuplicateArtifactReferenceException
import com.netflix.spinnaker.keel.exceptions.DuplicateResourceIdException
import com.netflix.spinnaker.keel.exceptions.MissingEnvironmentReferenceException
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import com.netflix.spinnaker.keel.test.TEST_API_V1
import com.netflix.spinnaker.keel.test.resource
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expectCatching
import strikt.assertions.isA
import strikt.assertions.isFailure

/**
 * Tests that involve creating, updating, or deleting things from two or more of the three repositories present.
 *
 * Tests that only apply to one repository should live in the repository-specific test classes.
 */
internal class DeliveryConfigValidatorTests : JUnit5Minutests {

    val configName = "my-config"
    val artifact = DockerArtifact(name = "org/image", deliveryConfigName = configName)
    val newArtifact = artifact.copy(reference = "myart")
    val firstResource = resource()
    val secondResource = resource()
    val firstEnv = Environment(name = "env1", resources = setOf(firstResource))
    val secondEnv = Environment(name = "env2", resources = setOf(secondResource))
    val deliveryConfig = DeliveryConfig(
      name = configName,
      application = "fnord",
      serviceAccount = "keel@spinnaker",
      artifacts = setOf(artifact),
      environments = setOf(firstEnv)
    )
    val subject = DeliveryConfigValidator()


  fun deliveryConfigValidatorTests() = rootContext<DeliveryConfigValidator> {
    fixture {
      DeliveryConfigValidator()
    }

    context("a delivery config with non-unique resource ids errors while persisting") {
      val submittedConfig = SubmittedDeliveryConfig(
        name = configName,
        application = "keel",
        serviceAccount = "keel@spinnaker",
        artifacts = setOf(artifact),
        environments = setOf(
          SubmittedEnvironment(
            name = "test",
            resources = setOf(
              SubmittedResource(
                kind = TEST_API_V1.qualify("whatever"),
                spec = DummyResourceSpec("test", "im a twin", "keel")
              )
            ),
            constraints = emptySet()
          ),
          SubmittedEnvironment(
            name = "prod",
            resources = setOf(
              SubmittedResource(
                kind = TEST_API_V1.qualify("whatever"),
                spec = DummyResourceSpec("test", "im a twin", "keel")
              )
            ),
            constraints = emptySet()
          )
        )
      )

      test("an error is thrown and config is deleted") {
        expectCatching {
          subject.validate(submittedConfig)
        }.isFailure()
          .isA<DuplicateResourceIdException>()

       // expectThat(subject.allResourceNames().size).isEqualTo(0)
      }
    }

    context("a delivery config with non-unique artifact references errors while persisting") {
      // Two different artifacts with the same reference
      val artifacts = setOf(
        DockerArtifact(name = "org/thing-1", deliveryConfigName = configName, reference = "thing"),
        DockerArtifact(name = "org/thing-2", deliveryConfigName = configName, reference = "thing")
      )

      val submittedConfig = SubmittedDeliveryConfig(
        name = configName,
        application = "keel",
        serviceAccount = "keel@spinnaker",
        artifacts = artifacts,
        environments = setOf(
          SubmittedEnvironment(
            name = "test",
            resources = setOf(
              SubmittedResource(
                metadata = mapOf("serviceAccount" to "keel@spinnaker"),
                kind = TEST_API_V1.qualify("whatever"),
                spec = DummyResourceSpec(data = "o hai")
              )
            ),
            constraints = emptySet()
          )
        )
      )
      test("an error is thrown and config is deleted") {
        expectCatching {
          subject.validate(submittedConfig)
        }.isFailure()
          .isA<DuplicateArtifactReferenceException>()

       // expectThat(subject.allResourceNames().size).isEqualTo(0)
      }
    }

    context("a second delivery config for an app fails to persist") {
      val submittedConfig1 = SubmittedDeliveryConfig(
        name = configName,
        application = "keel",
        serviceAccount = "keel@spinnaker",
        artifacts = setOf(DockerArtifact(name = "org/thing-1", deliveryConfigName = configName, reference = "thing")),
        environments = setOf(
          SubmittedEnvironment(
            name = "test",
            resources = setOf(
              SubmittedResource(
                metadata = mapOf("serviceAccount" to "keel@spinnaker"),
                kind = TEST_API_V1.qualify("whatever"),
                spec = DummyResourceSpec(data = "o hai")
              )
            ),
            constraints = emptySet()
          )
        )
      )

      val submittedConfig2 = submittedConfig1.copy(name = "double-trouble")
//      test("an error is thrown and config is not persisted") {
//        subject.validate(submittedConfig1)
//        expectCatching {
//          subject.validate(submittedConfig2)
//        }.isFailure()
//          .isA<TooManyDeliveryConfigsException>()
//
//        //expectThat(subject.getDeliveryConfigForApplication("keel").name).isEqualTo(configName)
//      }
    }

    context("submitting delivery config with invalid environment name as a constraint") {
      val submittedConfig = SubmittedDeliveryConfig(
        name = configName,
        application = "keel",
        serviceAccount = "keel@spinnaker",
        artifacts = setOf(DockerArtifact(name = "org/thing-1", deliveryConfigName = configName, reference = "thing")),
        environments = setOf(
          SubmittedEnvironment(
            name = "test",
            resources = emptySet(),
            constraints = emptySet()
          ),
          SubmittedEnvironment(
            name = "test",
            resources = emptySet(),
            constraints = setOf(DependsOnConstraint(environment = "notARealEnvironment"))
          )
        )
      )

      test("an error is thrown and config is not persisted") {
        expectCatching {
          subject.validate(submittedConfig)
        }.isFailure()
          .isA<MissingEnvironmentReferenceException>()

//        expectCatching {
//          subject.getDeliveryConfig(configName)
//        }.isFailure()
//          .isA<NoSuchDeliveryConfigException>()
      }
    }
  }
}

