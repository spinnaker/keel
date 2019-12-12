plugins {
  java
}

repositories {
  jcenter()
  if (property("korkVersion").toString().endsWith("-SNAPSHOT")) {
    mavenLocal()
  }
}

dependencies {
  "implementation"(platform("com.netflix.spinnaker.kork:kork-bom:${property("korkVersion")}"))

  "implementation"("com.netflix.spinnaker.kork:kork-plugins-api")

  "compileOnly"("org.projectlombok:lombok")
  "annotationProcessor"("org.projectlombok:lombok")
}
