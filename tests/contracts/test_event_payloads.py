"""TASK:PLT-02: payload contracts per event_type from 03 section 5.3."""
import copy
from decimal import Decimal
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
EVENTS = ROOT / "contracts/events"
FIXTURES = ROOT / "contracts/fixtures"


def load_json(path):
    """Keep fractional tokens exact (Decimal): a float such as 9007199254740992.5 or 398000.0
    must stay non-integer so VND/integer fields reject it (03/05: no float money)."""
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)


def load_payload_validators():
    """Map each registered event_type to a validator for its payload (local refs only)."""
    schemas = [load_json(p) for p in EVENTS.glob("*.schema.json")]
    registry = Registry().with_resources((s["$id"], Resource.from_contents(s)) for s in schemas)
    base = "https://fashion.local/contracts/events/"
    entries = load_json(EVENTS / "registry.json")
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
    return load_json(FIXTURES / "valid" / name)


class EventPayloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validators = load_payload_validators()

    def test_every_valid_fixture_matches_its_registered_payload(self):
        fixtures = sorted((FIXTURES / "valid").glob("*.json"))
        self.assertTrue(fixtures, "no valid fixtures found")
        for path in fixtures:
            with self.subTest(fixture=path.name):
                event = load_json(path)
                self.assertEqual(payload_errors(self.validators, event), [])

    def test_every_valid_fixture_has_a_valid_envelope(self):
        envelope = load_json(EVENTS / "event-envelope.schema.json")
        validator = Draft202012Validator(envelope, format_checker=FormatChecker())
        for path in sorted((FIXTURES / "valid").glob("*.json")):
            with self.subTest(fixture=path.name):
                validator.validate(load_json(path))

    def test_every_event_schema_is_valid_draft_2020_12(self):
        for path in sorted(EVENTS.glob("*.schema.json")):
            with self.subTest(schema=path.name):
                Draft202012Validator.check_schema(load_json(path))

    def test_invalid_payloads_fail_for_declared_reason(self):
        cases = load_json(FIXTURES / "invalid/payload-cases.json")
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

    def test_item_quantity_99_is_the_accepted_boundary(self):
        event = load_fixture("order-created.json")
        event["payload"]["items"][0]["quantity"] = 99
        self.assertEqual(payload_errors(self.validators, event), [])

    def test_unregistered_event_type_is_rejected(self):
        event = load_fixture("event-envelope.json")
        event["event_type"] = "ORDER_SHIPPED_TYPO"
        self.assertEqual(payload_errors(self.validators, event), ["unregistered event_type ORDER_SHIPPED_TYPO"])


class NotifyOrderTest(unittest.TestCase):
    """TASK:NOT-01: the five order emails share one schema; ship_to and cancel_category are per-kind."""
    KINDS = ["confirmed", "preparing", "delivered", "cancelled", "returned"]

    @classmethod
    def setUpClass(cls):
        cls.validators = load_payload_validators()

    def errors(self, mutate, kind="preparing"):
        event = load_fixture("notify-order-%s.json" % kind)
        mutate(event["payload"])
        return payload_errors(self.validators, event)

    def test_each_kind_has_a_valid_example_routed_to_notification_events(self):
        registry = load_json(EVENTS / "registry.json")
        for kind in self.KINDS:
            with self.subTest(kind=kind):
                event = load_fixture("notify-order-%s.json" % kind)
                self.assertEqual(payload_errors(self.validators, event), [])
                entry = registry[event["event_type"]]
                self.assertEqual((entry["topic"], entry["partition_key"], entry["aggregate_id"]),
                                 ("notification.events", "dedupe_key", "order_id"))

    def test_confirmed_needs_ship_to_and_no_other_kind_may_carry_it(self):
        self.assertTrue(self.errors(lambda p: p["data"].pop("ship_to"), "confirmed"))
        ship_to = load_fixture("notify-order-confirmed.json")["payload"]["data"]["ship_to"]
        for kind in self.KINDS[1:]:
            with self.subTest(kind=kind):
                self.assertTrue(self.errors(lambda p: p["data"].update(ship_to=ship_to), kind))

    def test_cancel_category_is_required_on_cancelled_only_and_closed(self):
        self.assertTrue(self.errors(lambda p: p["data"].pop("cancel_category"), "cancelled"))
        self.assertTrue(self.errors(lambda p: p["data"].update(cancel_category="BECAUSE"), "cancelled"))
        self.assertTrue(self.errors(lambda p: p["data"].update(cancel_category="SHOP"), "returned"))

    def test_unknown_fields_and_bad_dedupe_key_are_rejected(self):
        self.assertTrue(self.errors(lambda p: p.update(reason="free text")))
        self.assertTrue(self.errors(lambda p: p["data"].update(reason="free text")))
        self.assertTrue(self.errors(lambda p: p.update(dedupe_key="order:1:shipped")))
        self.assertTrue(self.errors(lambda p: p["data"].update(payment_method="VNPAY")))


if __name__ == "__main__":
    unittest.main()
