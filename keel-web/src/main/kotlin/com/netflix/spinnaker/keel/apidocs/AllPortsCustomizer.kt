package com.netflix.spinnaker.keel.apidocs

import com.netflix.spinnaker.keel.api.ec2.AllPorts
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.springframework.stereotype.Component

@Component
class AllPortsCustomizer : BaseModelConverter() {
  override fun resolve(annotatedType: AnnotatedType, context: ModelConverterContext, chain: MutableIterator<ModelConverter>): Schema<*>? {
    val baseType = annotatedType.rawClass
    return if (baseType == AllPorts::class.java) {
      StringSchema().apply {
        enum = listOf("ALL")
      }
    } else {
      super.resolve(annotatedType, context, chain)
    }
  }
}
