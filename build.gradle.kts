import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
  repositories {
    jcenter()
    gradlePluginPortal()
    maven(url = "https://spinnaker.bintray.com/gradle")
  }
  dependencies {
    classpath("com.netflix.spinnaker.gradle:spinnaker-dev-plugin:${property("spinnakerGradleVersion")}")
    if (property("enablePublishing") == "true") {
      classpath("com.netflix.spinnaker.gradle:spinnaker-gradle-project:${property("spinnakerGradleVersion")}")
    }
  }
}

plugins {
  id("nebula.kotlin") version "1.3.70" apply false
  id("org.jetbrains.kotlin.plugin.allopen") version "1.3.70" apply false
  id("com.github.ben-manes.versions") version "0.28.0"
}

allprojects {
  apply(plugin = "spinnaker.base-project")
  if (property("enablePublishing") == "true") {
    apply(plugin = "spinnaker.project")
  }

  group = "com.netflix.spinnaker.keel"
}

subprojects {
  if (name != "keel-bom") {
    apply(plugin = "nebula.kotlin")

    repositories {
      jcenter()
      if (property("korkVersion").toString().endsWith("-SNAPSHOT")) {
        mavenLocal()
      }
    }

    dependencies {
      "annotationProcessor"(platform("com.netflix.spinnaker.kork:kork-bom:${property("korkVersion")}"))
      "annotationProcessor"("org.springframework.boot:spring-boot-configuration-processor")
      "implementation"(platform("com.netflix.spinnaker.kork:kork-bom:${property("korkVersion")}"))

      "implementation"("org.slf4j:slf4j-api")

      "testImplementation"("org.junit.platform:junit-platform-runner")
      "testImplementation"("org.junit.jupiter:junit-jupiter-api")
      "testImplementation"("io.mockk:mockk")

      "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
      "testRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine")
    }

    tasks.withType<KotlinCompile> {
      kotlinOptions {
        languageVersion = "1.3"
        jvmTarget = "1.8"
        freeCompilerArgs += "-progressive"
      }
    }

    tasks.withType<Test> {
      useJUnitPlatform {
        includeEngines("junit-jupiter")
      }
      testLogging {
        exceptionFormat = FULL
      }
    }
  }

  configurations.all {
    exclude("javax.servlet", "servlet-api")

    resolutionStrategy {
      var okHttpVersion = "4.4.1"
      force(
        "com.squareup.okhttp3:okhttp:$okHttpVersion",
        "com.squareup.okhttp3:okhttp-urlconnection:$okHttpVersion",
        "com.squareup.okhttp3:okhttp-sse:$okHttpVersion",
        "com.squareup.okhttp3:mockwebserver:$okHttpVersion",
        "com.squareup.okhttp3:logging-interceptor:$okHttpVersion")
    }
  }
}

defaultTasks(":keel-web:run")
