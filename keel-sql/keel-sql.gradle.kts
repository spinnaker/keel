import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
  `java-library`
  id("kotlin-spring")
  id("ch.ayedo.jooqmodelator") version "3.6.0"
  id("org.liquibase.gradle") version "2.0.2"
}

afterEvaluate {
  val parent = project.gradle.parent

  if (parent != null) {
    logger.lifecycle("Running from composite build. Will copy jOOQ generated files from parent project.")
    tasks.register<Copy>("copyGeneratedJooqFiles") {
      from("${parent.rootProject.projectDir}/keel-sql/src/generated/java")
      into("$projectDir/src/generated/java")
    }
  }

  tasks.getByName("compileKotlin") {
    dependsOn("generateJooqMetamodel")
    if (parent != null) {
      dependsOn("copyGeneratedJooqFiles")
    }
  }
}

sourceSets {
  main {
    java {
      srcDir("$projectDir/src/generated/java")
    }
  }
}

tasks.getByName<Delete>("clean") {
  delete.add("$projectDir/src/generated/java")
}

dependencies {
  implementation(project(":keel-core"))
  implementation("com.netflix.spinnaker.kork:kork-sql")
  implementation("org.springframework:spring-jdbc")
  implementation("org.springframework:spring-tx")
  implementation("org.jooq:jooq:3.11.11")
  implementation("com.zaxxer:HikariCP")
  implementation("org.liquibase:liquibase-core")
  implementation("com.netflix.spinnaker.kork:kork-sql")

  runtimeOnly("mysql:mysql-connector-java")

  testImplementation("com.netflix.spinnaker.kork:kork-sql-test")
  testImplementation("io.strikt:strikt-core")
  testImplementation(project(":keel-spring-test-support"))
  testImplementation(project(":keel-test"))
  testImplementation(project(":keel-core-test"))
  testImplementation(project(":keel-web")) {
    // avoid circular dependency which breaks Liquibase
    exclude(module = "keel-sql")
  }
  testImplementation("org.testcontainers:mysql")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

  jooqModelatorRuntime(platform("com.netflix.spinnaker.kork:kork-bom:${property("korkVersion")}"))
  jooqModelatorRuntime("mysql:mysql-connector-java")

  liquibaseRuntime("org.liquibase:liquibase-core:3.8.5")
  liquibaseRuntime("org.liquibase:liquibase-groovy-dsl:2.0.1")
  liquibaseRuntime("org.yaml:snakeyaml:1.25")
  liquibaseRuntime("mysql:mysql-connector-java:8.0.16")
  liquibaseRuntime(sourceSets.main.get().output)
}

jooqModelator {
  jooqVersion = "3.12.3"
  jooqEdition = "OSS"
  jooqConfigPath = "$projectDir/src/main/resources/jooqConfig.xml"
  jooqOutputPath = "$projectDir/src/generated/java"
  migrationEngine = "LIQUIBASE"
  migrationsPaths = listOf("$projectDir/src/main/resources/db")
  dockerTag = "mysql/mysql-server:5.7"
  dockerEnv = listOf("MYSQL_ROOT_PASSWORD=sa", "MYSQL_ROOT_HOST=%", "MYSQL_DATABASE=keel")
  dockerHostPort = 6603
  dockerContainerPort = 3306
}

// Don't enforce spotless for generated code
afterEvaluate {
  configure<SpotlessExtension> {
    java {
      targetExclude(fileTree("$projectDir/src/generated/java"))
    }
  }
}

liquibase {
  activities.register("main") {
    arguments = mapOf(
      "logLevel" to "info",
      "changeLogFile" to "db/databaseChangeLog.yml",
      "url" to "jdbc:mysql://localhost:3306/keel?useSSL=false&serverTimezone=UTC",
      "username" to "root",
      "password" to ""
    )
  }
  runList = "main"
}
