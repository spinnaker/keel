plugins {
  `java-library`
  id("kotlin-spring")
}

dependencies {
  implementation(project(":keel-core"))
  implementation(project(":keel-igor"))
  implementation(project(":keel-front50"))
  implementation(project(":keel-retrofit"))
  implementation(project(":keel-titus-api"))
  testImplementation("dev.minutest:minutest")
  testImplementation("io.strikt:strikt-core")
  testImplementation(project(":keel-test"))
}
