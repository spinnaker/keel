package com.netflix.spinnaker.keel.clouddriver

import com.netflix.spinnaker.keel.clouddriver.model.AutoScalingGroup
import com.netflix.spinnaker.keel.clouddriver.model.BaseModelParsingTest
import com.netflix.spinnaker.keel.clouddriver.model.Cluster
import com.netflix.spinnaker.keel.clouddriver.model.ClusterLaunchConfig
import com.netflix.spinnaker.keel.clouddriver.model.ClusterServerGroup
import com.netflix.spinnaker.keel.clouddriver.model.ClusterServerGroupCapacity
import com.netflix.spinnaker.keel.clouddriver.model.Tag

object ClusterTest : BaseModelParsingTest<Cluster>() {

  override val json = javaClass.getResource("/cluster.json")

  override val call: CloudDriverService.() -> Cluster? = {
    getCluster("keel", "mgmttest", "keel-test", "aws")
  }

  override val expected = Cluster(
    name = "keel-test",
    accountName = "mgmttest",
    targetGroups = emptyList(),
    serverGroups = listOf(
      ClusterServerGroup(
        name = "keel-test-v051",
        region = "us-west-2",
        zones = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
        launchConfig = ClusterLaunchConfig(
          ramdiskId = "",
          ebsOptimized = true,
          imageId = "ami-043f38d940d310dcb",
          userData = "TkVURkxJWF9BUFBfTUVUQURBVEE9ImFtaT1hbWktMDQzZjM4ZDk0MGQzMTBkY2ImYXNnPWtlZWwtdGVzdC12MDUxJnQ9MTU0NDYxMDY0MyZ0eXBlPWF3cyZ2ZXJzaW9uPTEiCk5FVEZMSVhfQVBQX01FVEFEQVRBX0I2ND0iWVcxcFBXRnRhUzB3TkRObU16aGtPVFF3WkRNeE1HUmpZaVpoYzJjOWEyVmxiQzEwWlhOMExYWXdOVEVtZEQweE5UUTBOakV3TmpRekpuUjVjR1U5WVhkekpuWmxjbk5wYjI0OU1RPT0iCk5FVEZMSVhfQVBQX01FVEFEQVRBX1NJRz0ic2lnPVZ1bFRONXpHdUE2Q294aGdGRE1FNzU5dVBRUFoxQWpaVFVKcGJBVGxlZmZQektwMXdLREVMNnJ0QUFMLTRITW5KV05nbmdkdEk2c2c3d0hMcXQtbHVkMlY5dExWQmNJaFE1eXRoQVNhRzBUWWNXdEVVNWhnUUx5ajd1WVFETERJblJ2UGRrbFdkZ09rN3dtbnFIWHBaVTFUOGRWS2loclVfX0U4ZFk2bWVSRzEtazA1bFhvMnFQbkFPb1hKUERyMGItUGRZZFFQdEhab2lGYXJTMVJpWDFqMWxIMlptMXRzQXo4LTVXaFdUZmoyYktMaDR0TGc0d2xDbjQxZ2pFVXlaNF9tOVZWV29EZnJXWHZyLWNla19scEk1dHBEUkZ2X3lFbTVOWFhkOUg2Z1l5T3RQUHduVG9QV2ltUGNZUTdDemJiNkkyUmJaRDFQUVZ3TW5pT3pUX1VVcG5jdDh5MTNmaVZSOUdOdk5kcHhhejVsVXlYRVIwX25GTVRrSkVTQWtvZE5KcWw1UV9KVi1rNlduVGZCb1E2al9KRHNRMXJsRlRBTkRYRnVYU3dSTlBWQUdpSFJhNVRSTXRjSTVpRlQwX2w3YVV0VjRjN3lOakNsTzZaSnJJUTlhdmpWNlJYeG9tMS1hME5FelFOd0dTcE1rUkw2cXo0a2FLLVEtUERBTElVWEFhM19ULUQwTnZQT01USWhhLUJETElQd3lMRXJYNWwxbVpYeHNRZ21jOVVxZkpzRDhwdkJBNnlEelNrQUFEWWdPMXByMW8wZjJrZnlvckJCMjl4MFFxb2dQdGRLVXFTTkctZGhtTjB4U2hWdG53dzA3b3BTOGU2Rmd6LS1wX2V5aC0tLVlNTXl0TUNzOGplV1NZZFE0a3U5OThTVXFQZFcxaTJySmU0JTNEJmtleUlEPTEmc0FsZz1TSEE1MTJ3aXRoUlNBYW5kTUdGMSIKTkVURkxJWF9BQ0NPVU5UPSJtZ210dGVzdCIKTkVURkxJWF9BQ0NPVU5UX1RZUEU9Im1nbXQiCk5FVEZMSVhfRU5WSVJPTk1FTlQ9InRlc3QiCk5FVEZMSVhfQVBQPSJrZWVsIgpORVRGTElYX0FQUFVTRVI9ImtlZWwiCk5FVEZMSVhfU1RBQ0s9InRlc3QiCk5FVEZMSVhfQ0xVU1RFUj0ia2VlbC10ZXN0IgpORVRGTElYX0RFVEFJTD0iIgpORVRGTElYX0FVVE9fU0NBTEVfR1JPVVA9ImtlZWwtdGVzdC12MDUxIgpORVRGTElYX0xBVU5DSF9DT05GSUc9ImtlZWwtdGVzdC12MDUxLTEyMTIyMDE4MTAzMDQzIgpFQzJfUkVHSU9OPSJ1cy13ZXN0LTIiCgo=",
          instanceType = "m4.large",
          keyName = "nf-keypair-521597827845-us-west-2",
          iamInstanceProfile = "keelInstanceProfile"
        ),
        asg = AutoScalingGroup(
          autoScalingGroupName = "keel-test-v051",
          defaultCooldown = 10,
          healthCheckType = "EC2",
          healthCheckGracePeriod = 600,
          tags = listOf(
            Tag("spinnaker:application", "keel"),
            Tag("spinnaker:stack", "test")
          ),
          terminationPolicies = listOf("Default")
        ),
        vpcId = "vpc-00f82b65",
        targetGroups = emptyList(),
        loadBalancers = listOf("keel-test-vpc0"),
        capacity = ClusterServerGroupCapacity(0, 0, 0),
        securityGroups = listOf("sg-0f1fd86b", "sg-2f7f5a52")
      ),
      ClusterServerGroup(
        name = "keel-test-v052",
        region = "us-west-2",
        zones = listOf("us-west-2a", "us-west-2b", "us-west-2c"),
        launchConfig = ClusterLaunchConfig(
          ramdiskId = "",
          ebsOptimized = true,
          imageId = "ami-03226e0b4f071a2dc",
          userData = "TkVURkxJWF9BUFBfTUVUQURBVEE9ImFtaT1hbWktMDMyMjZlMGI0ZjA3MWEyZGMmYXNnPWtlZWwtdGVzdC12MDUyJnQ9MTU0NzExNjM1NyZ0eXBlPWF3cyZ2ZXJzaW9uPTEiCk5FVEZMSVhfQVBQX01FVEFEQVRBX0I2ND0iWVcxcFBXRnRhUzB3TXpJeU5tVXdZalJtTURjeFlUSmtZeVpoYzJjOWEyVmxiQzEwWlhOMExYWXdOVEltZEQweE5UUTNNVEUyTXpVM0puUjVjR1U5WVhkekpuWmxjbk5wYjI0OU1RPT0iCk5FVEZMSVhfQVBQX01FVEFEQVRBX1NJRz0ic2lnPXVVWUJleFhDZWJzTWZPWDJ5ZlBSdXJXbDRfenVWRTkwU3BtOWdBZU1KR3FCM0s4S2N6VjhzT2VEclBvWFVseG93UjExaUlGYXFMU2tVbk1iOTBFTzhWWDk0Mm1GR0JmNi1kM0l1MmJjWDlCWWpYa0xOOXhhUkpMZk5QMERtZWp2N1RhYmR0aGF6dzV1RzJZbUtXS1Y0UUxQd2UwWDRqNmpMUDBnTmFfQlhReEZhR2FyUEM4LWxQUV9ZTUMtQkV6UEMzT0hob09LNjdOdWJTWHU5U1BWUWRrQzhOemtPSUlZY25QaU4tT0VNVTE5LVdfMTlxeG1tWF9TZ0xsbkluOHpMeW1tSDFDb2lBUFN1RmlHY0ZyTlZkQlhNVnpvQmNBbTUtM0lnRlRtTHk3ZlhSdjN0X1lOZUJmMDJXRFVrOEpGbVM2Tnpnc2VzWkszUXYzaFJMN0JrNTkwQzVmMXlKOHhnOUw3ZHJETDlzZ1gxYy1HZzJ5NHR3VG5ZS0Q3WEI3akhoRktnNWc0OTI4Y2p6b2FfdHFOVkRzLVV6MW5JUkxXcFlZZldGRFRGcUxDZlhkWkFCZHFwa3BRbDVaN2x5SzV0VEtQUE9zc1Q1QWxuMFMtd2U2RG1IMTljY2xuVVJ4R1ROUTZtb3VzU3Vsa3RXTGhfNlFLVkpZai0wczEzdUI5NU1kd2VjOWtGUmtFeXNBMjlNTzRDc0F5aXNVRUhIVElhdkQtNU4zWXl0bTNNeFcwcmhqbDJ3STcwSWZRRng1QjFqdmh3enpocjN0emEtZmZaN1JmalBQMXlhcmZ3QlZ1ZG43bXJjQTB3N3daNUFCRXZqcERPYjkweE16RlpzRVYyNjNuMnZxck1YWW1GVVB5YWZkWW52Mzk1d0J5MkIwbmpFS0t0SHhmYTJzJTNEJmtleUlEPTEmc0FsZz1TSEE1MTJ3aXRoUlNBYW5kTUdGMSIKTkVURkxJWF9BQ0NPVU5UPSJtZ210dGVzdCIKTkVURkxJWF9BQ0NPVU5UX1RZUEU9Im1nbXQiCk5FVEZMSVhfRU5WSVJPTk1FTlQ9InRlc3QiCk5FVEZMSVhfQVBQPSJrZWVsIgpORVRGTElYX0FQUFVTRVI9ImtlZWwiCk5FVEZMSVhfU1RBQ0s9InRlc3QiCk5FVEZMSVhfQ0xVU1RFUj0ia2VlbC10ZXN0IgpORVRGTElYX0RFVEFJTD0iIgpORVRGTElYX0FVVE9fU0NBTEVfR1JPVVA9ImtlZWwtdGVzdC12MDUyIgpORVRGTElYX0xBVU5DSF9DT05GSUc9ImtlZWwtdGVzdC12MDUyLTAxMTAyMDE5MTAzMjM3IgpFQzJfUkVHSU9OPSJ1cy13ZXN0LTIiCgo=",
          instanceType = "m4.large",
          keyName = "nf-keypair-521597827845-us-west-2",
          iamInstanceProfile = "keelInstanceProfile"
        ),
        asg = AutoScalingGroup(
          autoScalingGroupName = "keel-test-v052",
          defaultCooldown = 10,
          healthCheckType = "EC2",
          healthCheckGracePeriod = 600,
          tags = listOf(
            Tag("spinnaker:application", "keel"),
            Tag("spinnaker:stack", "test")
          ),
          terminationPolicies = listOf("Default")
        ),
        vpcId = "vpc-00f82b65",
        targetGroups = emptyList(),
        loadBalancers = listOf("keel-test-vpc0"),
        capacity = ClusterServerGroupCapacity(1, 1, 1),
        securityGroups = listOf("sg-0f1fd86b", "sg-2f7f5a52")
      )
    )
  )
}
