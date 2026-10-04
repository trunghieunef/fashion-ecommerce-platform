"""TASK:PLT-04.C: single-node staging stack stays private, encrypted and within the approved shape (16)."""
import unittest

from cfn import load

TEMPLATE = "infra/aws/cloudformation/staging.yaml"
ALLOWED_TYPES = {
    "AWS::EC2::VPC", "AWS::EC2::Subnet", "AWS::EC2::InternetGateway", "AWS::EC2::VPCGatewayAttachment",
    "AWS::EC2::RouteTable", "AWS::EC2::Route", "AWS::EC2::SubnetRouteTableAssociation",
    "AWS::EC2::SecurityGroup", "AWS::EC2::Instance", "AWS::EC2::EIP", "AWS::IAM::Role",
    "AWS::IAM::InstanceProfile", "AWS::S3::Bucket", "AWS::S3::BucketPolicy", "AWS::Budgets::Budget",
}


class StagingStackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.template = load(TEMPLATE)
        cls.resources = cls.template["Resources"]
        cls.parameters = cls.template["Parameters"]

    def of_type(self, kind):
        return [r for r in self.resources.values() if r["Type"] == kind]

    def test_only_reviewed_resource_types(self):
        types = {r["Type"] for r in self.resources.values()}
        self.assertTrue(types <= ALLOWED_TYPES, types - ALLOWED_TYPES)

    def test_ingress_is_https_and_http_only(self):
        for group in self.of_type("AWS::EC2::SecurityGroup"):
            for rule in group["Properties"].get("SecurityGroupIngress", []):
                with self.subTest(rule=rule):
                    self.assertEqual(rule["IpProtocol"], "tcp")
                    self.assertEqual(rule["FromPort"], rule["ToPort"])
                    self.assertIn(rule["FromPort"], {80, 443})

    def test_instance_is_fixed_size_imdsv2_and_encrypted(self):
        (instance,) = self.of_type("AWS::EC2::Instance")
        props = instance["Properties"]
        self.assertEqual(self.parameters["InstanceType"]["AllowedValues"], ["t3.large"])
        self.assertEqual(props["MetadataOptions"]["HttpTokens"], "required")
        self.assertEqual(props["MetadataOptions"]["HttpPutResponseHopLimit"], 1, "pods must not reach IMDS")
        (device,) = props["BlockDeviceMappings"]
        ebs = device["Ebs"]
        self.assertEqual((ebs["VolumeType"], ebs["VolumeSize"], ebs["Encrypted"]), ("gp3", 40, True))
        self.assertFalse(ebs["DeleteOnTermination"], "staging data volume is retained")
        self.assertNotIn("Default", self.parameters["AmiId"], "AMI is pinned at approval time")

    def test_user_data_installs_checksum_pinned_k3s_without_piping_a_script(self):
        (instance,) = self.of_type("AWS::EC2::Instance")
        script = str(instance["Properties"]["UserData"])
        self.assertIn("sha256sum -c", script)
        self.assertIn("${K3sSha256}", script)
        self.assertNotRegex(script, r"\|\s*(ba)?sh\b", "no piped installer script")
        self.assertNotIn("get.k3s.io", script)
        self.assertRegex(self.parameters["K3sSha256"]["AllowedPattern"], r"\{64\}")
        self.assertEqual(self.parameters["K3sVersion"]["Default"], "v1.35.8+k3s1")

    def test_first_boot_waits_for_internet_before_downloading_k3s(self):
        (name,) = [n for n, r in self.resources.items() if r["Type"] == "AWS::EC2::Instance"]
        depends = self.resources[name].get("DependsOn", [])
        self.assertTrue({"DefaultRoute", "PublicSubnetRoutes"} <= set(depends if isinstance(depends, list) else [depends]))
        script = str(self.resources[name]["Properties"]["UserData"])
        # The EIP is associated only after the instance exists: the download must retry, bounded.
        self.assertIn("--retry-all-errors", script)
        self.assertRegex(script, r"--retry-max-time \d+")

    def test_backups_do_not_outlive_the_retention_through_noncurrent_versions(self):
        (bucket,) = self.of_type("AWS::S3::Bucket")
        props = bucket["Properties"]
        (rule,) = props["LifecycleConfiguration"]["Rules"]
        self.assertEqual(rule["ExpirationInDays"], {"Ref": "BackupRetentionDays"})
        versioned = props.get("VersioningConfiguration", {}).get("Status") == "Enabled"
        # With versioning, Expiration only adds a delete marker and data lives on as noncurrent.
        extra_days = rule.get("NoncurrentVersionExpiration", {}).get("NoncurrentDays", 0) if versioned else 0
        self.assertEqual(extra_days, 0, "retention counts every version from creation (13 section 3)")
        self.assertFalse(versioned and "NoncurrentVersionExpiration" not in rule, "versions would never expire")

    def test_instance_role_is_read_only_registry_and_ssm(self):
        (role,) = self.of_type("AWS::IAM::Role")
        props = role["Properties"]
        self.assertEqual(props["ManagedPolicyArns"], ["arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"])
        actions = {a for p in props["Policies"] for s in p["PolicyDocument"]["Statement"]
                   for a in (s["Action"] if isinstance(s["Action"], list) else [s["Action"]])}
        self.assertTrue(actions <= {
            "ecr:GetAuthorizationToken", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
            "ecr:BatchCheckLayerAvailability", "s3:PutObject", "s3:GetObject", "s3:ListBucket",
        }, actions)

    def test_backup_bucket_is_private_encrypted_retained_and_tls_only(self):
        (bucket,) = self.of_type("AWS::S3::Bucket")
        self.assertEqual(bucket["DeletionPolicy"], "Retain")
        props = bucket["Properties"]
        self.assertEqual(set(props["PublicAccessBlockConfiguration"].values()), {True})
        self.assertIn("BucketEncryption", props)
        (policy,) = self.of_type("AWS::S3::BucketPolicy")
        (statement,) = policy["Properties"]["PolicyDocument"]["Statement"]
        self.assertEqual(statement["Effect"], "Deny")
        self.assertEqual(statement["Condition"], {"Bool": {"aws:SecureTransport": "false"}})

    def test_backup_retention_must_be_chosen_inside_the_po_range(self):
        retention = self.parameters["BackupRetentionDays"]
        self.assertNotIn("Default", retention, "PO has not chosen a value inside 30-90 days (13 section 3)")
        self.assertEqual((retention["MinValue"], retention["MaxValue"]), (30, 90))

    def test_budget_alerts_before_credits(self):
        (budget,) = self.of_type("AWS::Budgets::Budget")
        spec = budget["Properties"]["Budget"]
        self.assertFalse(spec["CostTypes"]["IncludeCredit"], "track usage before credits (16 section 3.3)")
        alerts = {(n["Notification"]["NotificationType"], n["Notification"]["Threshold"])
                  for n in budget["Properties"]["NotificationsWithSubscribers"]}
        self.assertEqual(alerts, {("ACTUAL", 50), ("ACTUAL", 80), ("ACTUAL", 100), ("FORECASTED", 100)})


if __name__ == "__main__":
    unittest.main()
