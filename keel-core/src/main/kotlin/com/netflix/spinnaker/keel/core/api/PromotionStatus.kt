package com.netflix.spinnaker.keel.core.api

enum class PromotionStatus {
  /**
   * Waiting on constraint evaluation before being approved
   */
  PENDING,

  /**
   * Has passed constraints successfully, and wil be deployed imminently
   */
  APPROVED,

  /**
   * Deploying in the environment
   */
  DEPLOYING,

  /**
   * Currently deployed in the environment
   */
  CURRENT,

  /**
   * Was deployed in the environment, but was replaced by a new version
   */
  PREVIOUS,

  /**
   * Never allowed to be in the environment again
   */
  VETOED,

  /**
   * Was approved for the environment, but a newer version was deployed instead
   */
  SUPERSEDED
}
