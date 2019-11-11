package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.kork.sql.test.SqlTestUtil.initDatabase
import org.jooq.SQLDialect.MYSQL
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.MySQLContainerProvider

internal fun initTestDatabase() = initDatabase(
  mySQLContainer.authenticatedJdbcUrl,
  MYSQL
)

private val mySQLContainer = MySQLContainerProvider()
  .newInstance("5.7.22")
  .withDatabaseName("keel")
  .withUsername("keel_service")
  .withPassword("whatever")
  .also { it.start() }

@Suppress("UsePropertyAccessSyntax")
private val JdbcDatabaseContainer<*>.authenticatedJdbcUrl: String
  get() = "${getJdbcUrl()}?user=${getUsername()}&password=${getPassword()}"
