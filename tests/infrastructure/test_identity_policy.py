"""TASK:PLT-04.D: GitHub OIDC build role can only push the named ECR repositories (16 section 4.2)."""
import fnmatch
import unittest

from cfn import load, resolve_sub

TEMPLATE = "infra/aws/cloudformation/identity-and-ecr.yaml"
REPO = "trunghieunef/fashion-ecommerce-platform"
ECR_PUSH = {
    "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
    "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage",
}


def as_list(value):
    return value if isinstance(value, list) else [value]


class IdentityPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.template = load(TEMPLATE)
        cls.resources = cls.template["Resources"]
        cls.role = next(r["Properties"] for r in cls.resources.values() if r["Type"] == "AWS::IAM::Role")

    def assume_allowed(self, sub, aud="sts.amazonaws.com"):
        """Evaluate the trust policy for a GitHub OIDC token (StringEquals / StringLike only)."""
        claims = {"token.actions.githubusercontent.com:sub": sub, "token.actions.githubusercontent.com:aud": aud}
        for statement in self.role["AssumeRolePolicyDocument"]["Statement"]:
            if statement["Effect"] != "Allow" or statement["Action"] != "sts:AssumeRoleWithWebIdentity":
                continue
            ok = True
            for operator, conditions in statement["Condition"].items():
                for key, expected in conditions.items():
                    patterns = [resolve_sub(self.template, p) for p in as_list(expected)]
                    actual = claims.get(key)
                    if operator == "StringEquals":
                        ok &= actual in patterns
                    elif operator == "StringLike":
                        ok &= any(fnmatch.fnmatchcase(actual or "", p) for p in patterns)
                    else:
                        self.fail("unexpected condition operator " + operator)
            if ok:
                return True
        return False

    def test_protected_environment_subject_can_assume(self):
        self.assertTrue(self.assume_allowed(f"repo:{REPO}:environment:staging-publish"))

    def test_other_subjects_are_denied(self):
        for sub in (
            f"repo:{REPO}:pull_request",
            f"repo:{REPO}:ref:refs/heads/main",
            f"repo:{REPO}:ref:refs/heads/feature",
            f"repo:{REPO}:ref:refs/tags/v1.0.0",
            f"repo:{REPO}:environment:production",
            "repo:attacker/fashion-ecommerce-platform:environment:staging-publish",
            f"repo:{REPO}-fork:environment:staging-publish",
        ):
            with self.subTest(sub=sub):
                self.assertFalse(self.assume_allowed(sub))

    def test_wrong_audience_is_denied(self):
        self.assertFalse(self.assume_allowed(f"repo:{REPO}:environment:staging-publish", aud="sts.example"))

    def test_trust_policy_uses_exact_match_only(self):
        for statement in self.role["AssumeRolePolicyDocument"]["Statement"]:
            self.assertNotIn("StringLike", statement.get("Condition", {}))

    def test_permissions_only_push_named_repositories(self):
        repos = {name for name, r in self.resources.items() if r["Type"] == "AWS::ECR::Repository"}
        self.assertEqual(repos, {"CatalogRepository", "GatewayRepository", "StorefrontRepository"})
        statements = [s for p in self.role["Policies"] for s in p["PolicyDocument"]["Statement"]]
        for statement in statements:
            self.assertEqual(statement["Effect"], "Allow")
            actions = set(as_list(statement["Action"]))
            resources = as_list(statement["Resource"])
            if actions == {"ecr:GetAuthorizationToken"}:
                self.assertEqual(resources, ["*"], "GetAuthorizationToken has no resource scope")
                continue
            self.assertTrue(actions <= ECR_PUSH, actions - ECR_PUSH)
            self.assertEqual({r["Fn::GetAtt"][0] for r in resources}, repos)
            self.assertEqual({r["Fn::GetAtt"][1] for r in resources}, {"Arn"})

    def test_repositories_are_immutable_scanned_and_retained(self):
        for name, resource in self.resources.items():
            if resource["Type"] != "AWS::ECR::Repository":
                continue
            with self.subTest(repository=name):
                props = resource["Properties"]
                self.assertEqual(props["ImageTagMutability"], "IMMUTABLE")
                self.assertTrue(props["ImageScanningConfiguration"]["ScanOnPush"])
                self.assertIn("LifecyclePolicy", props)
                self.assertEqual(resource["DeletionPolicy"], "Retain")


if __name__ == "__main__":
    unittest.main()
