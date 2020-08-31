plugins {
  kotlin("jvm")
}

dependencies {
  api(project(":keel-api"))

  implementation("com.fasterxml.jackson.core:jackson-databind")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

  testImplementation("io.strikt:strikt-jackson")
}
