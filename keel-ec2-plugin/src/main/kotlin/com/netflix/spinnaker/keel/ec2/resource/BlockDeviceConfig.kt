package com.netflix.spinnaker.keel.ec2.resource

import org.springframework.stereotype.Component

@Component
class BlockDeviceConfig {
  /**
   * Return the size of the /dev/sdb EBS volume (in GB) based on the instance type
   */
  fun volumeSizeGb(instanceType: String) : Int? =
    when(instanceType) {
      "a1.medium"    ->  40
      "a1.large"     ->  40
      "a1.xlarge"    ->  80
      "a1.2xlarge"   ->  80
      "a1.4xlarge"   -> 120

      "c5a.large"    ->  40
      "c5a.xlarge"   ->  80
      "c5a.2xlarge"  ->  80
      "c5a.4xlarge"  -> 120
      "c5a.8xlarge"  -> 120
      "c5a.12xlarge" -> 120
      "c5a.16xlarge" -> 120
      "c5a.24xlarge" -> 120

      "c6g.medium"   -> 40
    "c6g.large"    -> 40
    "c6g.xlarge"   -> 80
    "c6g.2xlarge"  -> 80
    "c6g.4xlarge"  -> 120
    "c6g.8xlarge"  -> 120
    "c6g.12xlarge" -> 120
    "c6g.16xlarge" -> 120
    "c6g.metal"    -> 120

    "g3s.xlarge"  -> 80
    "g3.4xlarge"  -> 120
    "g3.8xlarge"  -> 120
    "g3.16xlarge" -> 120

    "inf1.xlarge"   -> 80
    "inf1.2xlarge"  -> 80
    "inf1.6xlarge"  -> 120
    "inf1.24xlarge" -> 120

    "m4.large"    -> 40
    "m4.xlarge"   -> 80
    "m4.2xlarge"  -> 80
    "m4.4xlarge"  -> 120
    "m4.10xlarge" -> 120
    "m4.16xlarge" -> 120

    "m5.large"    -> 40
    "m5.xlarge"   -> 80
    "m5.2xlarge"  -> 80
    "m5.4xlarge"  -> 120
    "m5.8xlarge"  -> 120
    "m5.12xlarge" -> 120
    "m5.16xlarge" -> 120
    "m5.24xlarge" -> 120

    "m5n.large"    -> 40
    "m5n.xlarge"   -> 80
    "m5n.2xlarge"  -> 80
    "m5n.4xlarge"  -> 120
    "m5n.8xlarge"  -> 120
    "m5n.12xlarge" -> 120
    "m5n.16xlarge" -> 120
    "m5n.24xlarge" -> 120

    "m5a.large"    -> 40
    "m5a.xlarge"   -> 80
    "m5a.2xlarge"  -> 80
    "m5a.4xlarge"  -> 120
    "m5a.8xlarge"  -> 120
    "m5a.12xlarge" -> 120
    "m5a.16xlarge" -> 120
    "m5a.24xlarge" -> 120


    "m6g.medium"   -> 40
    "m6g.large"    -> 40
    "m6g.xlarge"   -> 80
    "m6g.2xlarge"  -> 80
    "m6g.4xlarge"  -> 120
    "m6g.8xlarge"  -> 120
    "m6g.12xlarge" -> 120
    "m6g.16xlarge" -> 120
    "m6g.metal"    -> 120

    "r4.large"    -> 40
    "r4.xlarge"   -> 80
    "r4.2xlarge"  -> 80
    "r4.4xlarge"  -> 120
    "r4.8xlarge"  -> 120
    "r4.16xlarge" -> 120

    "r5.large"    -> 40
    "r5.xlarge"   -> 80
    "r5.2xlarge"  -> 80
    "r5.4xlarge"  -> 120
    "r5.8xlarge"  -> 120
    "r5.12xlarge" -> 120
    "r5.16xlarge" -> 120
    "r5.24xlarge" -> 120

    "r5n.large"    -> 40
    "r5n.xlarge"   -> 80
    "r5n.2xlarge"  -> 80
    "r5n.4xlarge"  -> 120
    "r5n.8xlarge"  -> 120
    "r5n.12xlarge" -> 120
    "r5n.16xlarge" -> 120
    "r5n.24xlarge" -> 120

    "r5a.large"    -> 40
    "r5a.xlarge"   -> 80
    "r5a.2xlarge"  -> 80
    "r5a.4xlarge"  -> 120
    "r5a.8xlarge"  -> 120
    "r5a.12xlarge" -> 120
    "r5a.16xlarge" -> 120
    "r5a.24xlarge" -> 120

    "r6g.medium"   -> 40
    "r6g.large"    -> 40
    "r6g.xlarge"   -> 80
    "r6g.2xlarge"  -> 80
    "r6g.4xlarge"  -> 120
    "r6g.8xlarge"  -> 120
    "r6g.12xlarge" -> 120
    "r6g.16xlarge" -> 120
    "r6g.metal"    -> 120

    "p2.xlarge"   -> 80
    "p2.8xlarge"  -> 120
    "p2.16xlarge" -> 120

    "p3.2xlarge"  -> 80
    "p3.8xlarge"  -> 120
    "p3.16xlarge" -> 120

    "p3dn.24xlarge" -> 120



      else -> null
    }
}
