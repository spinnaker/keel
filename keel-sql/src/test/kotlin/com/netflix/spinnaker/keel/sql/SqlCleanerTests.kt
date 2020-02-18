package com.netflix.spinnaker.keel.sql

// internal object SqlCleanerTests : CleanerTests<SqlDeliveryConfigRepository, SqlResourceRepository, SqlArtifactRepository>() {
//  private val testDatabase = initTestDatabase()
//  private val jooq = testDatabase.context
//  private val objectMapper = configuredObjectMapper()
//  private val retryProperties = RetryProperties(1, 0)
//  private val sqlRetry = SqlRetry(SqlRetryProperties(retryProperties, retryProperties))
//
//  override fun createDeliveryConfigRepository(resourceTypeIdentifier: ResourceTypeIdentifier): SqlDeliveryConfigRepository =
//    SqlDeliveryConfigRepository(jooq, Clock.systemDefaultZone(), DummyResourceTypeIdentifier, objectMapper, sqlRetry)
//
//  override fun createResourceRepository(): SqlResourceRepository =
//    SqlResourceRepository(jooq, Clock.systemDefaultZone(), DummyResourceTypeIdentifier, objectMapper, sqlRetry)
//
//  override fun createArtifactRepository(): SqlArtifactRepository =
//    SqlArtifactRepository(jooq, Clock.systemDefaultZone(), objectMapper, sqlRetry)
//
//  override fun flush() {
//    SqlTestUtil.cleanupDb(jooq)
//  }
//
//  @JvmStatic
//  @AfterAll
//  fun shutdown() {
//    testDatabase.dataSource.close()
//  }
// }
