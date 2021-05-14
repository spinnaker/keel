plugins {
  `java-library`
  id("kotlin-spring")
  application
}

apply(plugin = "io.spinnaker.package")

apply(plugin = "com.netflix.dgs.codegen")

tasks.withType<com.netflix.graphql.dgs.codegen.gradle.GenerateJavaTask> {
  schemaPaths = mutableListOf("${projectDir}/src/main/resources/schema")
  packageName = "com.netflix.spinnaker.keel.graphql"
  typeMapping = mutableMapOf("InstantTime" to "java.time.Instant", "JSON" to "kotlin.Any")
}

dependencies {
  api(project(":keel-core"))
  api(project(":keel-clouddriver"))
  api(project(":keel-artifact"))
  api(project(":keel-sql"))
  api(project(":keel-docker"))
  api(project(":keel-echo"))
  api(project(":keel-igor"))
  api(project(":keel-lemur"))
  api(project(":keel-slack"))

  implementation(project(":keel-bakery-plugin"))
  implementation(project(":keel-ec2-plugin"))
  implementation(project(":keel-titus-plugin"))
  implementation(project(":keel-schema-generator"))
  implementation(project(":keel-scm"))

  implementation("io.spinnaker.kork:kork-web")
  implementation("io.spinnaker.kork:kork-artifacts")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.security:spring-security-config")
  implementation("io.spinnaker.fiat:fiat-api:${property("fiatVersion")}")
  implementation("io.spinnaker.fiat:fiat-core:${property("fiatVersion")}")
  implementation("net.logstash.logback:logstash-logback-encoder")
  implementation("io.swagger.core.v3:swagger-annotations:2.1.2")
  implementation("org.apache.maven:maven-artifact:3.6.3")
  implementation("io.spinnaker.kork:kork-plugins")
  implementation("com.slack.api:bolt-servlet:1.6.0")
  implementation("com.graphql-java:graphql-java-extended-scalars:16.0.0")
  implementation("com.netflix.graphql.dgs:graphql-dgs-spring-boot-starter:3.9.3")

  runtimeOnly("io.spinnaker.kork:kork-runtime") {
    // these dependencies weren't previously being included, keeping them out for now, if there
    // is a need for them in the future these excludes are easy enough to delete...
    exclude(mapOf("group" to "io.spinnaker.kork", "module" to "kork-swagger"))
    exclude(mapOf("group" to "io.spinnaker.kork", "module" to "kork-stackdriver"))
    exclude(mapOf("group" to "io.spinnaker.kork", "module" to "kork-secrets-aws"))
    exclude(mapOf("group" to "io.spinnaker.kork", "module" to "kork-secrets-gcp"))
  }
  runtimeOnly("io.springfox:springfox-boot-starter:3.0.0")

  testImplementation("io.strikt:strikt-jackson")
  testImplementation(project(":keel-test"))
  testImplementation(project(":keel-core-test"))
  testImplementation(project(":keel-spring-test-support"))
  testImplementation(project(":keel-retrofit"))
  testImplementation(project(":keel-clouddriver"))
  testImplementation("io.spinnaker.kork:kork-security")
  testImplementation("com.squareup.okhttp3:mockwebserver")
  testImplementation("org.testcontainers:mysql:${property("testContainersVersion")}")
  testImplementation("com.networknt:json-schema-validator:1.0.43")
  testImplementation("io.spinnaker.kork:kork-plugins")
  testRuntimeOnly(project(":keel-ec2-plugin"))
}

application {
  mainClassName = "com.netflix.spinnaker.keel.MainKt"
}
