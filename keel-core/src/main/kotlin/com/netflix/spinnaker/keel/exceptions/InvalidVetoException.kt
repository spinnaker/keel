package com.netflix.spinnaker.keel.exceptions

import org.omg.CORBA.UserException

class InvalidVetoException(application: String, environment: String, reference: String, version: String) :
  UserException("Unable to veto artifact version $version with reference $reference in environment " +
  "$environment and application $application. Is this version pinned?")
