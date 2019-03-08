package com.netflix.spinnaker.keel.sqs

import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.DeleteMessageResult
import com.amazonaws.services.sqs.model.Message
import com.amazonaws.services.sqs.model.ReceiveMessageRequest
import com.amazonaws.services.sqs.model.ReceiveMessageResult
import com.netflix.spinnaker.config.SqsProperties
import com.netflix.spinnaker.keel.annealing.ResourceActuator
import com.netflix.spinnaker.keel.api.ResourceName
import com.netflix.spinnaker.keel.api.SPINNAKER_API_V1
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.argWhere
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.reset
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.timeout
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyBlocking
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEqualTo

internal class SqsResourceCheckListenerTests : JUnit5Minutests {

  val message = ResourceCheckMessage("ec2:cluster:prod:ap-south-1:keel", SPINNAKER_API_V1, "cluster")
  val objectMapper = configuredObjectMapper()
  val sqsClient: AmazonSQS = mock()
  val actuator: ResourceActuator = mock()

  fun tests() = rootContext<SqsResourceCheckListener> {

    fixture {
      SqsResourceCheckListener(
        sqsClient,
        "queueURL",
        SqsProperties(),
        objectMapper,
        actuator
      )
    }

    before {
      sqsClient.stub {
        on { deleteMessage(any()) } doReturn DeleteMessageResult()
      }
    }

    after {
      onApplicationDown()
      reset(sqsClient, actuator)
    }

    context("actuator succeeds") {
      before {
        sqsClient.stub {
          on {
            receiveMessage(argWhere<ReceiveMessageRequest> { it.queueUrl == "queueURL" })
          } doReturn enqueuedMessages(message) doReturn enqueuedMessages()
        }

        onApplicationUp()
      }

      test("invokes the actuator") {
        verifyBlocking(actuator, timeout(1000)) {
          checkResource(message.name.let(::ResourceName), message.apiVersion, message.kind)
        }
      }

      test("deletes the message from the queue") {
        verifyEventually(sqsClient)
          .deleteMessage(check {
            expect {
              that(it.queueUrl).isEqualTo("queueURL")
              that(it.receiptHandle).isEqualTo("receiptHandle-0")
            }
          })
      }
    }

    context("can't parse the message") {
      before {
        sqsClient.stub {
          on {
            receiveMessage(argWhere<ReceiveMessageRequest> { it.queueUrl == "queueURL" })
          } doReturn enqueuedMessages("SOME RANDOM JUNK", message) doReturn enqueuedMessages()
        }

        onApplicationUp()
      }

      test("goes on to process the valid message") {
        verifyBlocking(actuator, timeout(1000)) {
          checkResource(message.name.let(::ResourceName), message.apiVersion, message.kind)
        }
      }

      test("deletes the valid message but not the bad one") {
        verifyEventually(sqsClient).deleteMessage(check {
          expectThat(it.receiptHandle).isEqualTo("receiptHandle-1")
        })
        verify(sqsClient, never()).deleteMessage(argWhere {
          it.receiptHandle == "receiptHandle-0"
        })
      }
    }

    context("actuator fails") {
      before {
        sqsClient.stub {
          on {
            receiveMessage(argWhere<ReceiveMessageRequest> { it.queueUrl == "queueURL" })
          } doReturn enqueuedMessages(message, message.copy(name = "ec2:security-group:prod:ap-south-1:keel", kind = "security-group")) doReturn enqueuedMessages()
        }
        actuator.stub {
          onBlocking { checkResource(message.name.let(::ResourceName), message.apiVersion, message.kind) } doThrow IllegalStateException("o noes")
        }

        onApplicationUp()
      }

      test("goes on to process the next message") {
        verifyBlocking(actuator, timeout(1000)) {
          checkResource(message.name.let(::ResourceName), message.apiVersion, message.kind)
        }
      }

      test("deletes the successfully handled message but not the failed one") {
        verifyEventually(sqsClient).deleteMessage(check {
          expectThat(it.receiptHandle).isEqualTo("receiptHandle-1")
        })
        verify(sqsClient, never()).deleteMessage(argWhere {
          it.receiptHandle == "receiptHandle-0"
        })
      }
    }
  }

  private fun enqueuedMessages(vararg bodies: Any): ReceiveMessageResult {
    return ReceiveMessageResult()
      .withMessages(
        bodies.mapIndexed { index, body ->
          Message()
            .withBody(objectMapper.writeValueAsString(body))
            .withReceiptHandle("receiptHandle-$index")
        }
      )
  }

  private fun <T> verifyEventually(mock: T) = verify(mock, timeout(1000))
}
