package com.netflix.spinnaker.keel.rest.dgs

import graphql.schema.idl.RuntimeWiring

import com.netflix.graphql.dgs.DgsRuntimeWiring

import com.netflix.graphql.dgs.DgsComponent
import graphql.scalars.ExtendedScalars


@DgsComponent
class LongScalarRegistration {
  @DgsRuntimeWiring
  fun addScalar(builder: RuntimeWiring.Builder): RuntimeWiring.Builder {
    return builder.scalar(ExtendedScalars.Json)
  }
}
