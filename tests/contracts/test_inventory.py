"""TASK:INV-01/INV-02: stock and reservation contracts and canonical request boundaries."""
from pathlib import Path
from datetime import datetime
import json
import unittest
import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"


class InventoryContractTest(unittest.TestCase):
    def load(self):
        path = ROOT / "contracts/openapi/inventory.yaml"
        self.assertTrue(path.exists(), "inventory contract is missing")
        return yaml.safe_load(path.read_text(encoding="utf-8"))

    def validator(self, schema):
        registry = Registry()
        for filename in ("inventory.yaml", "common.yaml"):
            doc = yaml.safe_load((ROOT / "contracts/openapi" / filename).read_text(encoding="utf-8"))
            registry = registry.with_resource(BASE + filename, Resource.from_contents(doc, default_specification=DRAFT202012))
        return Draft202012Validator({"$ref": BASE + "inventory.yaml#/components/schemas/" + schema},
                                   registry=registry, format_checker=FormatChecker())

    def test_nine_implemented_operations_and_permissions(self):
        doc = self.load()
        expected = {("get", "/admin/api/v1/inventory/skus"),
                    ("get", "/admin/api/v1/inventory/skus/{sku}/transactions"),
                    ("post", "/admin/api/v1/inventory/adjust"),
                    ("post", "/admin/api/v1/inventory/import"),
                    ("get", "/internal/api/v1/inventory/skus/{sku}"),
                    ("post", "/internal/api/v1/inventory/reserve"),
                    ("post", "/internal/api/v1/inventory/commit"),
                    ("post", "/internal/api/v1/inventory/release"),
                    ("get", "/internal/api/v1/inventory/reservations/{order_id}")}
        actual = {(method, path) for path, item in doc["paths"].items() for method in item if method != "parameters"}
        self.assertEqual(actual, expected)
        for method, path in expected:
            op = doc["paths"][path][method]
            self.assertEqual(op["x-implementation-status"], "implemented")
            self.assertTrue(op["security"])
            if method == "post":
                self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])
            if path.startswith("/internal/"):
                for response in op["responses"].values():
                    self.assertEqual(response["headers"]["Cache-Control"]["schema"]["const"], "no-store")

    def test_reserve_request_is_closed_and_bounded(self):
        validator = self.validator("ReserveRequest")
        good = {"order_id": "4c8a1f2e-0000-4000-8000-000000000010", "order_no": "ORD202610000001",
                "items": [{"sku": "SKU-1", "quantity": 2}], "expires_at": "2026-10-16T10:15:00.000Z",
                "request_hash": "a" * 64}
        self.assertEqual(list(validator.iter_errors(good)), [])
        bad_items = [{"sku": "SKU-1", "quantity": 1}] * 101
        for change in ({"items": []}, {"items": bad_items}, {"items": [{"sku": "SKU-1", "quantity": 100}]},
                       {"items": [{"sku": "SKU-1", "quantity": 0}]}, {"request_hash": "A" * 64},
                       {"order_no": "x" * 33}, {"expires_at": "2026-10-16T10:15:00+07:00"}, {"extra": 1}):
            self.assertTrue(list(validator.iter_errors(good | change)), change)

    def test_release_and_reservation_shapes(self):
        schemas = self.load()["components"]["schemas"]
        self.assertEqual(set(schemas["ReleaseRequest"]["required"]),
                         {"order_id", "original_reservation", "original_request_hash", "reason"})
        self.assertEqual(set(schemas["Reservation"]["required"]),
                         {"reservation_id", "order_id", "order_no", "status", "expires_at", "items", "version",
                          "committed_at", "released_at", "release_reason", "restore_required"})
        self.assertEqual(schemas["Reservation"]["properties"]["status"]["enum"],
                         ["ACTIVE", "COMMITTED", "RELEASED", "EXPIRED"])
        for name in ("ReserveRequest", "CommitRequest", "ReleaseRequest", "Reservation"):
            self.assertFalse(schemas[name]["additionalProperties"], name)

    def test_response_examples_and_common_error_envelope(self):
        doc = self.load()
        for path, item in doc["paths"].items():
            for method, op in item.items():
                if method == "parameters":
                    continue
                for status, response in op["responses"].items():
                    self.assertIn("X-Correlation-Id", response["headers"])
                    content = response["content"]["application/json"]
                    if int(status) >= 400:
                        self.assertEqual(content["schema"], {"$ref": "./common.yaml#/components/schemas/ApiError"})
                    else:
                        schema = content["schema"]["$ref"].split("/")[-1]
                        self.assertEqual(list(self.validator(schema).iter_errors(content["example"])), [])

    def test_reservation_command_examples_match_their_result_states(self):
        paths = self.load()["paths"]
        for command, status, timestamp in (("commit", "COMMITTED", "committed_at"),
                                           ("release", "RELEASED", "released_at")):
            with self.subTest(command=command):
                response = paths["/internal/api/v1/inventory/" + command]["post"]["responses"]["200"]
                data = response["content"]["application/json"]["example"]["data"]
                self.assertEqual(data["status"], status)
                self.assertIsNotNone(data[timestamp])
                self.assertFalse(data["restore_required"])
                if command == "release":
                    self.assertTrue(data["release_reason"])

    def test_expired_event_fixture_is_not_published_before_its_deadline(self):
        event = json.loads((ROOT / "contracts/fixtures/valid/inventory-released.json").read_text(encoding="utf-8"))
        self.assertEqual(event["payload"]["status"], "EXPIRED")
        self.assertGreaterEqual(datetime.fromisoformat(event["occurred_at"].replace("Z", "+00:00")),
                                datetime.fromisoformat(event["payload"]["expires_at"].replace("Z", "+00:00")))

    def test_adjust_is_closed_and_delta_is_bounded_nonzero_integer(self):
        doc = self.load()
        self.assertFalse(doc["components"]["schemas"]["AdjustRequest"]["additionalProperties"])
        validator = self.validator("AdjustRequest")
        good = {"sku": "sku-1", "delta": 5, "reason": "delivery"}
        self.assertEqual(list(validator.iter_errors(good)), [])
        for change in ({"delta": 0}, {"delta": 1.5}, {"delta": 2147483648}, {"reason": "x" * 501}, {"extra": True}):
            self.assertTrue(list(validator.iter_errors(good | change)))

    def test_stock_and_internal_fields_match_runtime(self):
        schemas = self.load()["components"]["schemas"]
        self.assertEqual(set(schemas["Stock"]["required"]),
                         {"sku", "variant_id", "product_id", "on_hand", "reserved", "available", "min_stock", "status", "version"})
        self.assertEqual(set(schemas["InternalStock"]["required"]), {"sku", "variant_id", "available", "version", "observed_at"})
        self.assertEqual(set(schemas["LedgerItem"]["required"]),
                         {"id", "type", "on_hand_delta", "reserved_delta", "reason", "actor_id", "order_id", "created_at"})
        for response in self.load()["paths"]["/internal/api/v1/inventory/skus/{sku}"]["get"]["responses"].values():
            self.assertEqual(response["headers"]["Cache-Control"]["schema"]["const"], "no-store")

    def test_import_limits_and_atomic_scope_are_documented(self):
        op = self.load()["paths"]["/admin/api/v1/inventory/import"]["post"]
        self.assertIn("413", op["responses"])
        schema = op["requestBody"]["content"]["multipart/form-data"]["schema"]
        self.assertEqual(set(schema["required"]), {"file", "reason"})
        self.assertEqual(schema["properties"]["file"]["maxLength"], 131072)
        self.assertIn("1000", op["description"])


if __name__ == "__main__":
    unittest.main()
