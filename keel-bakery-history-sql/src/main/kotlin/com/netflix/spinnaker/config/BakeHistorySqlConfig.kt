package com.netflix.spinnaker.config

import com.netflix.spinnaker.keel.bakehistory.sql.SqlBakeHistory
import com.netflix.spinnaker.keel.bakery.artifact.BakeHistory
import com.netflix.spinnaker.keel.sql.SqlRetry
import com.netflix.spinnaker.kork.sql.config.SqlRetryProperties
import java.time.Clock
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@ConditionalOnProperty(
  "keel.plugins.bakery.enabled",
  "sql.enabled"
)
@Import(SqlRetryProperties::class)
class BakeHistorySqlConfig {
  @Autowired
  lateinit var sqlRetryProperties: SqlRetryProperties

  @Bean
  fun sqlBakeHistory(jooq: DSLContext, clock: Clock): BakeHistory =
    SqlBakeHistory(jooq, SqlRetry(sqlRetryProperties), clock)
}
