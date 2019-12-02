plugins {
  `java-library`
  id("kotlin-spring")
}

dependencies {
  implementation(project(":keel-core"))
  implementation(project(":keel-plugin"))
  implementation(project(":keel-clouddriver"))
  implementation(project(":keel-igor"))
  implementation(project(":keel-orca"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
  implementation("com.netflix.spinnaker.kork:kork-security")
  implementation("com.netflix.frigga:frigga")

  testImplementation(project(":keel-test"))
  testImplementation("dev.minutest:minutest")
  testImplementation("io.strikt:strikt-core")
  testImplementation(project(":keel-spring-test-support"))
}
