plugins {
  `java-library`
  id("kotlin-spring")
}

dependencies {
  implementation(project(":keel-api"))
  implementation(project(":keel-core")) // TODO: ideally not
  implementation(project(":keel-clouddriver"))
  implementation(project(":keel-igor"))
  implementation(project(":keel-orca"))
  implementation(project(":keel-artifact"))
  implementation("com.netflix.spinnaker.kork:kork-exceptions")
  implementation("com.netflix.spinnaker.kork:kork-security")
  implementation("com.netflix.frigga:frigga")

  testImplementation(project(":keel-test"))
  testImplementation("dev.minutest:minutest")
  testImplementation("io.strikt:strikt-mockk")
  testImplementation(project(":keel-spring-test-support"))
}
