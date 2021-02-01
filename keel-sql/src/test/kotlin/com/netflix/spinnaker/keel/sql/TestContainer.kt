package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.initDatabase
import org.jooq.SQLDialect.MYSQL
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.MySQLContainerProvider

internal val testDatabase by lazy {
  initDatabase(mySQLContainer.authenticatedJdbcUrl, MYSQL)
}

internal val mySQLContainer = MySQLContainerProvider()
  .newInstance("5.7.22")
  .withDatabaseName("keel")
  .withUsername("keel_service")
  .withPassword("whatever")
  .withReuse(true)
  .withNetwork(null)
  .also { it.start() }

@Suppress("UsePropertyAccessSyntax")
private val JdbcDatabaseContainer<*>.authenticatedJdbcUrl: String
  get() = "${getJdbcUrl()}?user=${getUsername()}&password=${getPassword()}"
