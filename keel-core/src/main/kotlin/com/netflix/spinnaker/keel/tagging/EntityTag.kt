/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.tagging

data class EntityTag(
  val namespace: String,
  val value: TagValue?,
  val valueType: String = "object",
  val category: String = "Keel",
  val name: String = "spinnaker_ui_alert:keel_managed"
) : Tag

data class TagValue(
  val message: String,
  val type: String = "alert"
)

data class EntityRef(
  val entityType: String,
  val cloudProvider: String,
  val entityId: String,
  val region: String,
  val account: String
)

data class EntityTagCollection(
  val ref: EntityRef,
  val tags: List<EntityTag>
)
