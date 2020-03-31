package com.netflix.spinnaker.keel.exceptions

import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.kork.exceptions.SystemException
import com.netflix.spinnaker.kork.exceptions.UserException

sealed class NoSuchEntityException(override val message: String?) :
  SystemException(message)

sealed class NoSuchResourceException(override val message: String?) :
  NoSuchEntityException(message)

class NoSuchResourceId(id: String) :
  NoSuchResourceException("No resource with id $id exists in the database")

class NoSuchApplication(application: String) :
  NoSuchEntityException("No resource with application name $application exists in the database")

class NoSuchArtifactException(name: String, type: ArtifactType) :
  NoSuchEntityException("No $type artifact named $name is registered") {
  constructor(artifact: DeliveryArtifact) : this(artifact.name, artifact.type)
}

class ArtifactReferenceNotFoundException(deliveryConfig: String, reference: String, type: ArtifactType) :
  NoSuchEntityException("No $type artifact with reference $reference in delivery config $deliveryConfig is registered")

class ArtifactNotFoundException(name: String, type: ArtifactType, reference: String, deliveryConfig: String?) :
  NoSuchEntityException("No $type artifact named $name with reference $reference in delivery config $deliveryConfig is registered") {
  constructor(artifact: DeliveryArtifact) : this(artifact.name, artifact.type, artifact.reference, artifact.deliveryConfigName)
}

class ArtifactAlreadyRegistered(name: String, type: ArtifactType) :
  UserException("The $type artifact $name is already registered") {
  constructor(artifact: DeliveryArtifact) : this(artifact.name, artifact.type)
}

sealed class NoSuchDeliveryConfigException(message: String) :
  NoSuchEntityException(message)

class NoSuchDeliveryConfigName(name: String) :
  NoSuchDeliveryConfigException("No delivery config named $name exists in the database")

class NoDeliveryConfigForApplication(application: String) :
  NoSuchDeliveryConfigException("No delivery config for application $application exists in the database")

class NoMatchingArtifactException(deliveryConfigName: String, type: ArtifactType, reference: String) :
  NoSuchEntityException("No artifact with reference $reference and type $type found in delivery config $deliveryConfigName")

class TooManyDeliveryConfigsException(application: String, existing: String) :
  UserException("A delivery config already exists for application $application, and we only allow one per application - please delete existing configs $existing before submitting a new config")

class OrphanedResourceException(id: String) :
  SystemException("Resource $id exists without being a part of a delivery config")
