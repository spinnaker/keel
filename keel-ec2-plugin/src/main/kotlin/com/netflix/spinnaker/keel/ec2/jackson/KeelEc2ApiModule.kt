package com.netflix.spinnaker.keel.ec2.jackson

import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.Serializers
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.CidrRule
import com.netflix.spinnaker.keel.api.ec2.ClassicLoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.ClusterDependencies
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec.HealthSpec
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec.ServerGroupSpec
import com.netflix.spinnaker.keel.api.ec2.CrossAccountReferenceRule
import com.netflix.spinnaker.keel.api.ec2.CustomizedMetricSpecification
import com.netflix.spinnaker.keel.api.ec2.IngressPorts
import com.netflix.spinnaker.keel.api.ec2.InstanceProvider
import com.netflix.spinnaker.keel.api.ec2.ReferenceRule
import com.netflix.spinnaker.keel.api.ec2.Scaling
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupRule
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupSpec
import com.netflix.spinnaker.keel.api.ec2.ServerGroup.ActiveServerGroupImage
import com.netflix.spinnaker.keel.api.ec2.ServerGroup.BuildInfo
import com.netflix.spinnaker.keel.api.ec2.ServerGroup.Health
import com.netflix.spinnaker.keel.api.ec2.StepAdjustment
import com.netflix.spinnaker.keel.api.ec2.StepScalingPolicy
import com.netflix.spinnaker.keel.api.ec2.TargetGroupAttributes
import com.netflix.spinnaker.keel.api.ec2.TargetTrackingPolicy
import com.netflix.spinnaker.keel.api.ec2.old.ApplicationLoadBalancerV1Spec
import com.netflix.spinnaker.keel.api.ec2.old.ClusterV1Spec
import com.netflix.spinnaker.keel.api.support.ExtensionRegistry
import com.netflix.spinnaker.keel.api.support.register
import com.netflix.spinnaker.keel.ec2.jackson.mixins.ApplicationLoadBalancerSpecMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.BuildInfoMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.ClassicLoadBalancerSpecMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.ClusterDependenciesMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.ClusterSpecMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.ClusterV1SpecMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.CustomizedMetricSpecificationMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.HealthMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.HealthSpecMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.InstanceProviderMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.ReferenceRuleMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.ScalingMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.SecurityGroupSpecMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.ServerGroupSpecMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.StepAdjustmentMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.StepScalingPolicyMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.TargetGroupAttributesMixin
import com.netflix.spinnaker.keel.ec2.jackson.mixins.TargetTrackingPolicyMixin
import com.netflix.spinnaker.keel.jackson.SerializationExtensionRegistry

fun ObjectMapper.registerKeelEc2ApiModule(
  extensionRegistry: ExtensionRegistry, serializationExtensionRegistry: SerializationExtensionRegistry
): ObjectMapper {
  extensionRegistry.registerEc2Subtypes()

  with(serializationExtensionRegistry) {
    // deserializers
    register(ActiveServerGroupImage::class.java, ActiveServerGroupImageDeserializer())
    register(IngressPorts::class.java, IngressPortsDeserializer())
    register(SecurityGroupSpec::class.java, SecurityGroupSpecDeserializer())
    register(SecurityGroupRule::class.java, SecurityGroupRuleDeserializer())
    // serializers
    register(IngressPorts::class.java, IngressPortsSerializer())
  }
  return registerModule(KeelEc2ApiModule(serializationExtensionRegistry))
}

fun ExtensionRegistry.registerEc2Subtypes() {
  // Note that the discriminators below are not used as sub-types are determined by the custom deserializer above
  register<SecurityGroupRule>(ReferenceRule::class.java, "reference")
  register<SecurityGroupRule>(CrossAccountReferenceRule::class.java, "cross-account")
  register<SecurityGroupRule>(CidrRule::class.java, "cidr")
}

class KeelEc2ApiModule(
  private val serializationExtensionRegistry: SerializationExtensionRegistry
) : SimpleModule("Keel EC2 API") {
  override fun setupModule(context: SetupContext) {
    with(context) {
      addSerializers(KeelEc2ApiSerializers(serializationExtensionRegistry))
      addDeserializers(KeelEc2ApiDeserializers(serializationExtensionRegistry))

      setMixInAnnotations<ApplicationLoadBalancerSpec, ApplicationLoadBalancerSpecMixin>()
      // same annotations are required for this legacy model, so it can reuse the same mixin
      setMixInAnnotations<ApplicationLoadBalancerV1Spec, ApplicationLoadBalancerSpecMixin>()
      setMixInAnnotations<BuildInfo, BuildInfoMixin>()
      setMixInAnnotations<ClassicLoadBalancerSpec, ClassicLoadBalancerSpecMixin>()
      setMixInAnnotations<ClusterDependencies, ClusterDependenciesMixin>()
      setMixInAnnotations<ClusterSpec, ClusterSpecMixin>()
      setMixInAnnotations<ClusterV1Spec, ClusterV1SpecMixin>()
      setMixInAnnotations<CustomizedMetricSpecification, CustomizedMetricSpecificationMixin>()
      setMixInAnnotations<Health, HealthMixin>()
      setMixInAnnotations<HealthSpec, HealthSpecMixin>()
      setMixInAnnotations<InstanceProvider, InstanceProviderMixin>()
      setMixInAnnotations<ReferenceRule, ReferenceRuleMixin>()
      setMixInAnnotations<Scaling, ScalingMixin>()
      setMixInAnnotations<SecurityGroupSpec, SecurityGroupSpecMixin>()
      setMixInAnnotations<ServerGroupSpec, ServerGroupSpecMixin>()
      setMixInAnnotations<StepAdjustment, StepAdjustmentMixin>()
      setMixInAnnotations<StepScalingPolicy, StepScalingPolicyMixin>()
      setMixInAnnotations<TargetGroupAttributes, TargetGroupAttributesMixin>()
      setMixInAnnotations<TargetTrackingPolicy, TargetTrackingPolicyMixin>()
    }
    super.setupModule(context)
  }
}

internal class KeelEc2ApiSerializers(
  private val serializationExtensionRegistry: SerializationExtensionRegistry
) : Serializers.Base() {
  override fun findSerializer(config: SerializationConfig, type: JavaType, beanDesc: BeanDescription): JsonSerializer<*>? =
    serializationExtensionRegistry.serializers.keys.find {
      it.isAssignableFrom(type.rawClass)
    }
      ?.let { serializationExtensionRegistry.serializers[it] }
}

internal class KeelEc2ApiDeserializers(
  private val serializationExtensionRegistry: SerializationExtensionRegistry
) : Deserializers.Base() {
  override fun findBeanDeserializer(
    type: JavaType,
    config: DeserializationConfig,
    beanDesc: BeanDescription
  ): JsonDeserializer<*>? =
    serializationExtensionRegistry.deserializers[type.rawClass]
}

private inline fun <reified TARGET, reified MIXIN> Module.SetupContext.setMixInAnnotations() {
  setMixInAnnotations(TARGET::class.java, MIXIN::class.java)
}
