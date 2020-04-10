plugins {
  `java-library`
  id("kotlin-spring")
}

dependencies {
  implementation(project(":keel-bakery-plugin"))
  implementation(project(":keel-sql"))

  testImplementation("com.netflix.spinnaker.kork:kork-sql-test")
  testImplementation("io.strikt:strikt-core")
  testImplementation(project(":keel-spring-test-support"))
  testImplementation(project(":keel-test"))
  testImplementation(project(":keel-core-test"))

  testImplementation(project(":keel-web")) {
    // avoid circular dependency which breaks Liquibase
    exclude(module = "keel-bakery-history-sql")
  }
}
