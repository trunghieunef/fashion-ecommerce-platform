"""TASK:PLT-02: topic/partition key per event_type from 03 section 5.2, for outbox producers."""
from pathlib import Path
import re
import unittest

from test_event_payloads import load_json, load_payload_validators, payload_errors

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts/fixtures/valid"
REGISTRY = load_json(ROOT / "contracts/events/registry.json")


def topics_from_03():
    """event_type -> (topic, first partition key word) from the 03 section 5.2 table."""
    text = (ROOT / "docs/design/03_interfaces.md").read_text(encoding="utf-8")
    section = text.split("### 5.2", 1)[1].split("### 5.3", 1)[0]
    routes = {}
    for row in section.splitlines():
        cells = [c.strip() for c in row.strip().strip("|").split("|")]
        match = re.fullmatch(r"([a-z]+\.[a-z]+) / (\w+).*", cells[0])
        if match and len(cells) > 2:
            for event_type in re.findall(r"[A-Z][A-Z_]+[A-Z]", cells[2]):
                routes[event_type] = match.groups()
    return routes


class EventRoutingTest(unittest.TestCase):
    def test_registry_topic_and_partition_key_match_03(self):
        routes = topics_from_03()
        for event_type, entry in REGISTRY.items():
            with self.subTest(event_type=event_type):
                self.assertIn(event_type, routes, "event_type missing from 03 §5.2")
                self.assertIsInstance(entry, dict, "registry entry needs schema/topic/partition_key")
                self.assertEqual((entry["topic"], entry["partition_key"]), routes[event_type])

    def test_payload_without_partition_key_is_rejected(self):
        validators = load_payload_validators()
        for path in sorted(FIXTURES.glob("*.json")):
            event = load_json(path)
            with self.subTest(fixture=path.name):
                event["payload"].pop(REGISTRY[event["event_type"]]["partition_key"])
                self.assertTrue(payload_errors(validators, event), "partition key must be required")

    def test_envelope_aggregate_id_is_the_declared_payload_id(self):
        for path in sorted(FIXTURES.glob("*.json")):
            event = load_json(path)
            with self.subTest(fixture=path.name):
                entry = REGISTRY[event["event_type"]]
                self.assertIn("aggregate_id", entry, "registry must name the payload field used as aggregate_id")
                self.assertEqual(event["aggregate_id"], event["payload"][entry["aggregate_id"]])


if __name__ == "__main__":
    unittest.main()
