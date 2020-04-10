package com.netflix.spinnaker.keel.bakehistory.sql.spring

import com.netflix.spinnaker.keel.KeelApplication
import com.netflix.spinnaker.keel.bakehistory.sql.SqlBakeHistory
import com.netflix.spinnaker.keel.bakery.artifact.BakeHistory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK
import org.springframework.test.context.junit.jupiter.SpringExtension
import strikt.api.expectThat
import strikt.assertions.isA

@ExtendWith(SpringExtension::class)
@SpringBootTest(
  classes = [KeelApplication::class],
  webEnvironment = MOCK,
  properties = [
    "keel.plugins.bakery.enabled=true",
    "sql.enabled=true",
    "sql.connection-pools.default.jdbc-url=jdbc:tc:mysql:5.7.22://somehostname:someport/databasename",
    "sql.migration.jdbc-url=jdbc:tc:mysql:5.7.22://somehostname:someport/databasename",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver"
  ]
)
internal class SpringStartupTests {

  @Autowired
  lateinit var bakeHistory: BakeHistory

  @Test
  fun `uses SqlBakeHistory`() {
    expectThat(bakeHistory).isA<SqlBakeHistory>()
  }
}
