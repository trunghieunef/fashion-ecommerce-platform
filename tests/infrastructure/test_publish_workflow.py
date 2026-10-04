"""TASK:PLT-04.D: image publication runs only for tested main commits via OIDC (16 section 4.2)."""
import re
import unittest

import yaml

from cfn import ROOT

WORKFLOW = ROOT / ".github/workflows/publish-images.yml"


class PublishWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.workflow = yaml.safe_load(cls.text)
        # PyYAML reads the bare key `on` as boolean True.
        cls.triggers = cls.workflow.get("on", cls.workflow.get(True))
        cls.job = cls.workflow["jobs"]["publish"]

    def test_runs_only_after_application_ci_on_main(self):
        self.assertEqual(set(self.triggers), {"workflow_run"})
        run = self.triggers["workflow_run"]
        self.assertEqual(run["workflows"], ["Application CI"])
        self.assertEqual(run["branches"], ["main"])
        self.assertEqual(run["types"], ["completed"])

    def test_job_requires_success_and_explicit_enablement(self):
        condition = self.job["if"]
        self.assertIn("github.event.workflow_run.conclusion == 'success'", condition)
        self.assertIn("github.event.workflow_run.event == 'push'", condition)
        self.assertIn("vars.AWS_PUBLISH_ENABLED == 'true'", condition)

    def test_oidc_only_in_protected_environment_job(self):
        self.assertEqual(self.workflow["permissions"], {"contents": "read"})
        self.assertEqual(self.job["permissions"], {"contents": "read", "id-token": "write"})
        self.assertEqual(self.job["environment"], "staging-publish")

    def test_checks_out_the_tested_commit(self):
        checkout = next(s for s in self.job["steps"] if s.get("uses", "").startswith("actions/checkout@"))
        self.assertEqual(checkout["with"]["ref"], "${{ github.event.workflow_run.head_sha }}")
        self.assertFalse(checkout["with"]["persist-credentials"])

    def test_actions_are_pinned_and_no_long_lived_secrets(self):
        for uses in re.findall(r"uses:\s*(\S+)", self.text):
            with self.subTest(uses=uses):
                self.assertRegex(uses, r"@[0-9a-f]{40}$")
        self.assertNotRegex(self.text, r"\$\{\{\s*secrets\.")
        self.assertNotIn(":latest", self.text)


if __name__ == "__main__":
    unittest.main()
