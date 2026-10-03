"""TASK:PLT-02: executable envelope boundaries from 03 section 5.1."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]


class EventEnvelopeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = json.loads((ROOT / "contracts/events/event-envelope.schema.json").read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        if "date-time" not in FormatChecker.checkers:
            raise RuntimeError("Install tests/contracts/requirements.txt for date-time validation")
        cls.validator = Draft202012Validator(schema, format_checker=FormatChecker())
        cls.example = json.loads((ROOT / "contracts/fixtures/valid/event-envelope.json").read_text(encoding="utf-8"))

    def test_valid_example(self):
        self.validator.validate(self.example)

    def test_each_required_field(self):
        for field in self.validator.schema["required"]:
            with self.subTest(field=field):
                value = copy.deepcopy(self.example)
                del value[field]
                self.assertTrue(any(e.validator == "required" for e in self.validator.iter_errors(value)))

    def test_invalid_fixtures_fail_for_declared_reason(self):
        cases = json.loads((ROOT / "contracts/fixtures/invalid/envelope-cases.json").read_text(encoding="utf-8"))
        for case in cases:
            with self.subTest(name=case["name"]):
                value = copy.deepcopy(self.example)
                value.update(case["changes"])
                errors = list(self.validator.iter_errors(value))
                self.assertTrue(any(
                    e.validator == case["validator"] and list(e.path) == case["path"]
                    for e in errors
                ), errors)

    def test_bigint_boundary(self):
        for field in ("aggregate_version", "aggregate_sequence"):
            with self.subTest(field=field):
                value = copy.deepcopy(self.example)
                value[field] = 2**63 - 1
                self.validator.validate(value)
                value[field] += 1
                self.assertTrue(any(e.validator == "maximum" for e in self.validator.iter_errors(value)))

    def test_same_version_can_have_next_sequence(self):
        next_event = copy.deepcopy(self.example)
        next_event["event_id"] = "22222222-2222-4222-8222-222222222222"
        next_event["aggregate_sequence"] += 1
        self.validator.validate(next_event)


if __name__ == "__main__":
    unittest.main()
