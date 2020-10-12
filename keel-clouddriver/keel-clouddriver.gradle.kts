plugins {
  `java-library`
  id("kotlin-spring")
}

dependencies {
  implementation(project(":keel-retrofit"))
  implementation(project(":keel-core"))
  implementation("com.netflix.spinnaker.kork:kork-moniker")
  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.core:jackson-annotations")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
  implementation("org.springframework:spring-context")
  implementation("org.springframework.boot:spring-boot-autoconfigure")
  implementation("com.netflix.frigga:frigga")

  testImplementation(project(":keel-retrofit-test-support"))
  testImplementation("com.squareup.retrofit2:retrofit-mock")
  testImplementation("com.squareup.okhttp3:mockwebserver")
  testImplementation("dev.minutest:minutest")
  testImplementation("io.strikt:strikt-core")

  runtime("org.jetbrains.kotlin:kotlin-reflect")
}
