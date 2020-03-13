package com.netflix.spinnaker.keel.sql

import org.jooq.Converter
import org.jooq.JSON

class JooqJsonToStringConverter : Converter<JSON, String> {
  override fun from(databaseObject: JSON): String {
    return databaseObject.data()
  }

  override fun to(userObject: String): JSON {
    return JSON.valueOf(userObject)
  }

  override fun fromType(): Class<JSON> {
    return JSON::class.java
  }

  override fun toType(): Class<String> {
    return String::class.java
  }
}
