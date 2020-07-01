package com.netflix.spinnaker.keel.ec2

import com.netflix.spinnaker.keel.api.ec2.Action
import com.netflix.spinnaker.keel.api.ec2.Condition
import com.netflix.spinnaker.keel.api.ec2.RedirectConfig
import com.netflix.spinnaker.keel.api.ec2.Rule
import com.netflix.spinnaker.keel.api.ec2.TargetGroupAttributes
import com.netflix.spinnaker.keel.clouddriver.model.ActiveServerGroupImage
import com.netflix.spinnaker.keel.clouddriver.model.ApplicationLoadBalancerModel
import com.netflix.spinnaker.keel.clouddriver.model.BuildInfo
import com.netflix.spinnaker.keel.clouddriver.model.InstanceCounts

internal fun ApplicationLoadBalancerModel.Rule.toSpec(): Rule =
  Rule(priority, conditions?.map { it.toSpec() }, actions.map { it.toSpec() }, default)

internal fun ApplicationLoadBalancerModel.Condition.toSpec(): Condition =
  Condition(field, values)

internal fun ApplicationLoadBalancerModel.Action.toSpec(): Action =
  Action(type, order, targetGroupName, redirectConfig?.toSpec())

internal fun ApplicationLoadBalancerModel.RedirectConfig.toSpec(): RedirectConfig =
  RedirectConfig(protocol, port, host, path, query, statusCode)

internal fun ApplicationLoadBalancerModel.TargetGroupAttributes.toSpec(): TargetGroupAttributes =
  TargetGroupAttributes(stickinessEnabled, deregistrationDelay, stickinessType, stickinessDuration, slowStartDurationSeconds, properties)

internal fun BuildInfo.toSpec(): com.netflix.spinnaker.keel.api.ec2.BuildInfo =
  com.netflix.spinnaker.keel.api.ec2.BuildInfo(packageName)

internal fun ActiveServerGroupImage.toSpec(): com.netflix.spinnaker.keel.api.ec2.ActiveServerGroupImage =
  com.netflix.spinnaker.keel.api.ec2.ActiveServerGroupImage(imageId, appVersion, baseImageVersion, name, imageLocation, description)

internal fun InstanceCounts.toSpec(): com.netflix.spinnaker.keel.api.ec2.InstanceCounts =
  com.netflix.spinnaker.keel.api.ec2.InstanceCounts(total, up, down, unknown, outOfService, starting)
