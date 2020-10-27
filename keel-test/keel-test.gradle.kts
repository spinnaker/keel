plugins {
  `java-library`
}

dependencies {
  api(project(":keel-api"))
  api(project(":keel-core"))
  api(project(":keel-artifact"))
  api(project(":keel-igor"))
  api(project(":keel-clouddriver"))
  implementation("io.mockk:mockk")
  implementation("org.junit.jupiter:junit-jupiter-api")
  implementation("io.strikt:strikt-core")
  implementation("dev.minutest:minutest")
  implementation(project(":keel-spring-test-support"))
}
