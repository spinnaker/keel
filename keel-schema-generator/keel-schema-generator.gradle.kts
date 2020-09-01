plugins {
  kotlin("jvm")
}

dependencies {
  api(project(":keel-api"))

  implementation("org.jetbrains.kotlin:kotlin-reflect")

  testImplementation("com.fasterxml.jackson.core:jackson-databind")
  testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  testImplementation("io.strikt:strikt-core")
}
