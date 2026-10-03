"""TASK:PLT-02: payload contracts per event_type from 03 section 5.3."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
EVENTS = ROOT / "contracts/events"
FIXTURES = ROOT / "contracts/fixtures"


def load_payload_validators():
    """Map each registered event_type to a validator for its payload (local refs only)."""
    schemas = [json.loads(p.read_text(encoding="utf-8")) for p in EVENTS.glob("*.schema.json")]
    registry = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in schemas)
    base = "https://fashion.local/contracts/events/"
    entries = json.loads((EVENTS / "registry.json").read_text(encoding="utf-8"))
    return {
        event_type: Draft202012Validator({"$ref": base + entry["schema"]}, registry=registry,
                                         format_checker=FormatChecker())
        for event_type, entry in entries.items()
    }


def payload_errors(validators, event):
    validator = validators.get(event["event_type"])
    if validator is None:
        return ["unregistered event_type " + event["event_type"]]
    return list(validator.iter_errors(event["payload"]))


def load_fixture(name):
    return json.loads((FIXTURES / "valid" / name).read_text(encoding="utf-8"))


class EventPayloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validators = load_payload_validators()

    def test_every_valid_fixture_matches_its_registered_payload(self):
        fixtures = sorted((FIXTURES / "valid").glob("*.json"))
        self.assertTrue(fixtures, "no valid fixtures found")
        for path in fixtures:
            with self.subTest(fixture=path.name):
                event = json.loads(path.read_text(encoding="utf-8"))
                self.assertEqual(payload_errors(self.validators, event), [])

    def test_every_valid_fixture_has_a_valid_envelope(self):
        envelope = json.loads((EVENTS / "event-envelope.schema.json").read_text(encoding="utf-8"))
        validator = Draft202012Validator(envelope, format_checker=FormatChecker())
        for path in sorted((FIXTURES / "valid").glob("*.json")):
            with self.subTest(fixture=path.name):
                validator.validate(json.loads(path.read_text(encoding="utf-8")))

    def test_every_event_schema_is_valid_draft_2020_12(self):
        for path in sorted(EVENTS.glob("*.schema.json")):
            with self.subTest(schema=path.name):
                Draft202012Validator.check_schema(json.loads(path.read_text(encoding="utf-8")))

    def test_invalid_payloads_fail_for_declared_reason(self):
        cases = json.loads((FIXTURES / "invalid/payload-cases.json").read_text(encoding="utf-8"))
        for case in cases:
            with self.subTest(name=case["name"]):
                event = load_fixture(case["fixture"])
                event["payload"].update(copy.deepcopy(case["changes"]))
                for field in case.get("remove", []):
                    del event["payload"][field]
                errors = payload_errors(self.validators, event)
                self.assertTrue(any(
                    e.validator == case["validator"] and list(e.path) == case["path"]
                    for e in errors if not isinstance(e, str)
                ), errors)

    def test_unregistered_event_type_is_rejected(self):
        event = load_fixture("event-envelope.json")
        event["event_type"] = "ORDER_SHIPPED_TYPO"
        self.assertEqual(payload_errors(self.validators, event), ["unregistered event_type ORDER_SHIPPED_TYPO"])


if __name__ == "__main__":
    unittest.main()
