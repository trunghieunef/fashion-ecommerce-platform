"""TASK:SHP-01: shipping public/admin and internal contracts (spec section 5)."""
from pathlib import Path
import unittest

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"
FILES = ("shipping.yaml", "shipping-internal.yaml", "common.yaml")
PUBLIC = {("post", "/api/v1/shipping/fee/estimate"), ("get", "/admin/api/v1/shipping/rules"),
          ("post", "/admin/api/v1/shipping/rules"), ("put", "/admin/api/v1/shipping/rules/{id}"),
          ("get", "/admin/api/v1/shipping/shipments"), ("get", "/admin/api/v1/shipping/shipments/{id}"),
          ("post", "/admin/api/v1/shipping/shipments/{id}/self-events")}
INTERNAL = {("post", "/internal/api/v1/shipping/quotes"), ("post", "/internal/api/v1/shipping/shipments"),
            ("get", "/internal/api/v1/shipping/shipments/by-order/{order_id}"),
            ("post", "/internal/api/v1/shipping/shipments/by-order/{order_id}/cancel")}


class ShippingContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docs = {name: yaml.safe_load((ROOT / "contracts/openapi" / name).read_text(encoding="utf-8")) for name in FILES}
        cls.registry = Registry().with_resources(
            (BASE + name, Resource.from_contents(d, default_specification=DRAFT202012)) for name, d in cls.docs.items())

    def resolve(self, value, current):
        if "$ref" not in value:
            return value
        filename, pointer = value["$ref"].split("#")
        node = self.docs[filename.removeprefix("./") or current]
        for part in pointer.strip("/").split("/"):
            node = node[part]
        return node

    def operations(self, name):
        for path, item in self.docs[name]["paths"].items():
            for method, op in item.items():
                if method != "parameters":
                    yield method, path, op

    def validator(self, ref):
        return Draft202012Validator({"$ref": ref}, registry=self.registry, format_checker=FormatChecker())

    def test_operations(self):
        self.assertEqual({(m, p) for m, p, _ in self.operations("shipping.yaml")}, PUBLIC)
        self.assertEqual({(m, p) for m, p, _ in self.operations("shipping-internal.yaml")}, INTERNAL)
        self.assertEqual(self.docs["shipping-internal.yaml"]["security"], [{"serviceToken": []}])
        estimate = self.docs["shipping.yaml"]["paths"]["/api/v1/shipping/fee/estimate"]["post"]
        self.assertEqual(estimate["security"], [])

    def test_every_response_is_no_store_and_examples_validate(self):
        for name in ("shipping.yaml", "shipping-internal.yaml"):
            for method, path, op in self.operations(name):
                for status, response in op["responses"].items():
                    with self.subTest(file=name, op=f"{method} {path}", status=status):
                        response = self.resolve(response, name)
                        headers = response["headers"]
                        self.assertEqual(self.resolve(headers["Cache-Control"], name)["schema"]["const"], "no-store")
                        content = response.get("content", {}).get("application/json")
                        if not content:
                            continue
                        target, pointer = content["schema"]["$ref"].split("#")
                        validator = self.validator(BASE + (target.removeprefix("./") or name) + "#" + pointer)
                        for example in content.get("examples", {}).values():
                            self.assertEqual([e.message for e in validator.iter_errors(example["value"])], [])

    def test_fee_and_shipment_requests_are_bounded(self):
        fee = self.validator(BASE + "shipping.yaml#/components/schemas/FeeRequest")
        ok = {"province_code": "79", "cod": True, "items": [{"variant_id": "11111111-1111-4111-8111-111111111111", "quantity": 1}]}
        self.assertEqual(list(fee.iter_errors(ok)), [])
        for bad in ({**ok, "items": []}, {**ok, "province_code": "7 9"}, {**ok, "cod": "yes"},
                    {**ok, "items": [{"variant_id": "11111111-1111-4111-8111-111111111111", "quantity": 100}]}):
            self.assertTrue(list(fee.iter_errors(bad)), bad)
        create = self.validator(BASE + "shipping-internal.yaml#/components/schemas/ShipmentCreate")
        body = {"order_id": "99999999-9999-4999-8999-999999999991", "order_no": "ORD1", "quote_id": "6a1b2c3d-0000-4000-8000-000000000001",
                "cod_amount": 0, "to_address": {"recipient_name": "A", "phone": "0900000000", "province_code": "79",
                                                "ward_code": "26734", "address_line": "1 Mẫu"}}
        self.assertEqual(list(create.iter_errors(body)), [])
        for bad in ({**body, "cod_amount": -1}, {**body, "to_address": {**body["to_address"], "phone": "call me"}},
                    {**body, "to_address": {**body["to_address"], "extra": "x"}}):
            self.assertTrue(list(create.iter_errors(bad)), bad)

    def test_rule_create_needs_key_and_update_states_active(self):
        create = self.docs["shipping.yaml"]["paths"]["/admin/api/v1/shipping/rules"]["post"]
        self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, create["parameters"])
        update = self.docs["shipping.yaml"]["components"]["schemas"]["RuleUpdate"]
        self.assertIn("active", update["required"])
        self.assertIn("expected_version", update["required"])

    def test_self_event_and_cancel_bodies(self):
        # TASK:SHP-02: self-events need a key, a known type and integer VND; cancel carries the create snapshot + reason.
        op = self.docs["shipping.yaml"]["paths"]["/admin/api/v1/shipping/shipments/{id}/self-events"]["post"]
        self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])
        event = self.validator(BASE + "shipping.yaml#/components/schemas/SelfEvent")
        ok = {"event_type": "COD_COLLECTED", "expected_version": 3, "amount": 275000, "evidence_ref": "BN-1"}
        self.assertEqual(list(event.iter_errors(ok)), [])
        for bad in ({**ok, "event_type": "SHIPPED"}, {**ok, "amount": "275000"}, {**ok, "amount": 0},
                    {**ok, "extra": 1}, {"event_type": "DELIVERED"}):
            self.assertTrue(list(event.iter_errors(bad)), bad)
        cancel = self.validator(BASE + "shipping-internal.yaml#/components/schemas/ShipmentCancel")
        body = {"order_id": "99999999-9999-4999-8999-999999999991", "order_no": "ORD1", "quote_id": "6a1b2c3d-0000-4000-8000-000000000001",
                "cod_amount": 0, "to_address": {"recipient_name": "A", "phone": "0900000000", "province_code": "79",
                                                "ward_code": "26734", "address_line": "1 Mẫu"}}
        self.assertTrue(list(cancel.iter_errors(body)), "reason is required")
        self.assertEqual(list(cancel.iter_errors({**body, "reason": "khách hủy"})), [])

    def test_rule_types(self):
        types = self.docs["shipping.yaml"]["components"]["schemas"]["RuleType"]["enum"]
        self.assertEqual(types, ["FLAT", "PROVINCE", "WEIGHT"])


if __name__ == "__main__":
    unittest.main()
