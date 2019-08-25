/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.yaml

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.springframework.http.MediaType
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter
import org.springframework.stereotype.Component

const val APPLICATION_YAML_VALUE = "application/x-yaml"
val APPLICATION_YAML: MediaType = MediaType.parseMediaType(APPLICATION_YAML_VALUE)

@Component
class YamlHttpMessageConverter(yamlMapper: YAMLMapper) :
  AbstractJackson2HttpMessageConverter(yamlMapper, APPLICATION_YAML)
