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
  id("nebula.kotlin") version "1.3.72" apply false
  id("org.jetbrains.kotlin.plugin.allopen") version "1.3.72" apply false
  id("com.github.ben-manes.versions") version "0.28.0"
  jacoco
}

jacoco {
  toolVersion = "0.8.5"
}

allprojects {
  apply(plugin = "spinnaker.base-project")
  if (property("enablePublishing") == "true") {
    apply(plugin = "spinnaker.project")
  }

  repositories {
    jcenter() {
      metadataSources {
        artifact()
        mavenPom()
      }
    }
    mavenCentral()
    if (property("korkVersion").toString().endsWith("-SNAPSHOT")) {
      mavenLocal()
    }
  }

  group = "com.netflix.spinnaker.keel"
}

subprojects {
  if (name != "keel-bom") {
    apply(plugin = "nebula.kotlin")
    apply(plugin = "jacoco")

    dependencies {
      "annotationProcessor"(platform("com.netflix.spinnaker.kork:kork-bom:${property("korkVersion")}"))
      "annotationProcessor"("org.springframework.boot:spring-boot-configuration-processor")
      "implementation"(platform("com.netflix.spinnaker.kork:kork-bom:${property("korkVersion")}"))

      "implementation"("org.slf4j:slf4j-api")

      "testImplementation"("org.junit.platform:junit-platform-runner")
      "testImplementation"("org.junit.jupiter:junit-jupiter-api")
      "testImplementation"("io.mockk:mockk")
      "testImplementation"("org.jacoco:org.jacoco.ant:0.8.5")

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

    val subproject = this
    tasks.withType<JacocoReport> {
      logger.info("Setting up jacoco report for ${subproject.name}")
      reports {
        xml.isEnabled = true
        csv.isEnabled = false
        html.isEnabled = false
      }
    }
  }

  configurations.all {
    exclude("javax.servlet", "servlet-api")

    resolutionStrategy {
      var okHttpVersion = "4.5.0"
      force(
        "com.squareup.okhttp3:okhttp:$okHttpVersion",
        "com.squareup.okhttp3:okhttp-urlconnection:$okHttpVersion",
        "com.squareup.okhttp3:okhttp-sse:$okHttpVersion",
        "com.squareup.okhttp3:mockwebserver:$okHttpVersion",
        "com.squareup.okhttp3:logging-interceptor:$okHttpVersion")
    }
  }
}

// Based on https://gist.github.com/tsjensen/d8b9ab9e6314ae2f63f4955c44399dad#gistcomment-3220383
fun codeCoverageProjects() = subprojects + project

task<JacocoMerge>("jacocoMerge") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Merge the JaCoCo data files from all sub-projects into one"

  afterEvaluate {
    val execFiles = objects.fileCollection()
    subprojects.forEach { subproject: Project ->
      val testTasks = subproject.tasks.withType<Test>()
      if (testTasks.isNotEmpty()) {
        logger.debug("jacocoMerge depends on: ${testTasks.map { ":${subproject.name}:${it.name}" }}")
        dependsOn(testTasks)

        testTasks.forEach { task: Test ->
          val extension = task.extensions.findByType(JacocoTaskExtension::class.java)
          extension?.let {
            logger.debug("jacocoMerge adding execution file ${it.destinationFile}")
            execFiles.from(it.destinationFile)
          }
        }
      }
    }
    executionData = execFiles
  }

  doFirst {
    // .exec files might be missing if a project has no tests. Filter in execution phase.
    executionData = executionData.filter { it.canRead() }
  }
}

task<JacocoReport>("jacocoAggregateReport") {
  dependsOn("jacocoMerge")
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Generates an aggregate JaCoCo report from all sub-projects"
  reports {
    csv.isEnabled = false
    xml.isEnabled = true
    html.isEnabled = true
  }

  afterEvaluate {
    val reportTasks = subprojects.flatMap { it.tasks.withType<JacocoReport>() }
    val jacocoMergeTask = tasks.named("jacocoMerge", JacocoMerge::class).get()
    val destFile = jacocoMergeTask.destinationFile
    logger.lifecycle("Using JaCoCo aggregate report file: $destFile")
    executionData.from(destFile)
    logger.debug("Adding jacoco report directories from: ${reportTasks.mapNotNull { ":${it.project.name}:${it.name}" }}")
    classDirectories.from(project.files(reportTasks.mapNotNull { it.classDirectories }))
    sourceDirectories.from(project.files(reportTasks.mapNotNull { it.sourceDirectories }))
  }
}

defaultTasks(":keel-web:run")
