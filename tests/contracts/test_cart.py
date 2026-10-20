"""TASK:CART-01: guest/member cart wire contract (spec section 5)."""
from pathlib import Path
import unittest

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"
OPERATIONS = {
    ("post", "/api/v1/carts"), ("get", "/api/v1/carts/mine"), ("get", "/api/v1/carts/{cart_id}"),
    ("delete", "/api/v1/carts/{cart_id}"), ("post", "/api/v1/carts/{cart_id}/items"),
    ("put", "/api/v1/carts/{cart_id}/items/{item_id}"), ("delete", "/api/v1/carts/{cart_id}/items/{item_id}"),
    ("post", "/api/v1/carts/merge"), ("post", "/api/v1/carts/{cart_id}/checkout-token"),  # ORD-01
}


class CartContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = ROOT / "contracts/openapi/cart.yaml"
        cls.doc = yaml.safe_load(path.read_text(encoding="utf-8")) if path.exists() else None
        docs = {name: yaml.safe_load((ROOT / "contracts/openapi" / name).read_text(encoding="utf-8"))
                for name in ("cart.yaml", "common.yaml") if (ROOT / "contracts/openapi" / name).exists()}
        cls.registry = Registry().with_resources(
            (BASE + name, Resource.from_contents(d, default_specification=DRAFT202012)) for name, d in docs.items())

    def resolve(self, value):
        if "$ref" not in value:
            return value
        filename, pointer = value["$ref"].split("#")
        node = self.doc if filename in ("", "./cart.yaml") else yaml.safe_load(
            (ROOT / "contracts/openapi" / filename.removeprefix("./")).read_text(encoding="utf-8"))
        for part in pointer.strip("/").split("/"):
            node = node[part]
        return node

    def validator(self, name):
        return Draft202012Validator({"$ref": BASE + "cart.yaml#/components/schemas/" + name},
                                    registry=self.registry, format_checker=FormatChecker())

    def test_operations_exist(self):
        self.assertIsNotNone(self.doc, "cart contract is missing")
        actual = {(m, p) for p, item in self.doc["paths"].items() for m in item if m != "parameters"}
        self.assertEqual(actual, OPERATIONS)

    def test_every_response_is_no_store(self):
        for path, item in self.doc["paths"].items():
            for method, op in item.items():
                if method == "parameters":
                    continue
                for status, response in op["responses"].items():
                    with self.subTest(op=f"{method} {path}", status=status):
                        headers = self.resolve(response).get("headers", {})
                        self.assertEqual(self.resolve(headers["Cache-Control"])["schema"]["const"], "no-store")

    def test_add_requires_idempotency_key_and_version(self):
        op = self.doc["paths"]["/api/v1/carts/{cart_id}/items"]["post"]
        params = [self.resolve(p) for p in op.get("parameters", [])]
        key = next(p for p in params if p["name"] == "Idempotency-Key")
        self.assertTrue(key["required"])
        body = self.resolve(op["requestBody"]["content"]["application/json"]["schema"])
        self.assertEqual(set(body["required"]), {"variant_id", "quantity", "expected_version"})
        for path, method in (("/api/v1/carts/{cart_id}/items/{item_id}", "delete"), ("/api/v1/carts/{cart_id}", "delete")):
            version = next(self.resolve(p) for p in self.doc["paths"][path][method]["parameters"]
                           if self.resolve(p)["name"] == "expected_version")
            self.assertTrue(version["required"])

    def test_quantity_bounds_and_cart_shape(self):
        add = self.validator("AddItemRequest")
        ok = {"variant_id": "11111111-1111-4111-8111-111111111111", "quantity": 1, "expected_version": 0}
        self.assertEqual(list(add.iter_errors(ok)), [])
        for q in (0, 100, 1.5):
            self.assertTrue(list(add.iter_errors({**ok, "quantity": q})), q)
        cart = self.validator("Cart")
        sample = {"cart_id": "11111111-1111-4111-8111-111111111111", "version": 1, "owner": "GUEST",
                  "expires_at": "2026-11-17T03:00:00Z", "preview_status": "OK", "subtotal_preview": 180000,
                  "shipping_fee_status": "NOT_CALCULATED", "items": [{
                      "item_id": "22222222-2222-4222-8222-222222222222", "variant_id": "11111111-1111-4111-8111-111111111111",
                      "sku": "TEE-M", "quantity": 2, "price_snapshot": 100000, "item_version": 0,
                      "preview": {"effective_price": 90000, "price_changed": True, "active": True, "available": 3,
                                  "observed_at": "2026-10-18T03:00:00Z"}}]}
        self.assertEqual(list(cart.iter_errors(sample)), [])
        self.assertTrue(list(cart.iter_errors({**sample, "owner": "ADMIN"})))
        self.assertTrue(list(cart.iter_errors({**sample, "preview_status": "MAYBE"})))
        # CART-03: the subtotal is an estimate or null; the shipping fee is never calculated by cart.
        self.assertEqual(list(cart.iter_errors({**sample, "subtotal_preview": None})), [])
        self.assertTrue(list(cart.iter_errors({**sample, "subtotal_preview": -1})))
        self.assertTrue(list(cart.iter_errors({**sample, "shipping_fee_status": "CALCULATED"})))
        self.assertTrue(list(cart.iter_errors({k: v for k, v in sample.items() if k != "shipping_fee_status"})))

    def test_merge_contract(self):
        op = self.doc["paths"]["/api/v1/carts/merge"]["post"]
        self.assertEqual(op["security"], [{"memberBearer": [], "guestCookie": []}])
        self.assertTrue(self.resolve(op["parameters"][0])["required"])
        body = self.validator("MergeRequest")
        ok = {"guest_cart_id": "11111111-1111-4111-8111-111111111111", "guest_expected_version": 1, "member_expected_version": 0}
        self.assertEqual(list(body.iter_errors(ok)), [])
        for field in ok:
            self.assertTrue(list(body.iter_errors({k: v for k, v in ok.items() if k != field})), field)
        self.assertTrue(list(body.iter_errors({**ok, "guest_expected_version": -1})))
        adjustment = self.validator("Adjustment")
        sample = {"variant_id": "11111111-1111-4111-8111-111111111111", "sku": "TEE-M", "reason": "OUT_OF_STOCK",
                  "requested": 2, "applied": 0}
        self.assertEqual(list(adjustment.iter_errors(sample)), [])
        self.assertTrue(list(adjustment.iter_errors({**sample, "reason": "DROPPED"})))
        self.assertIn("Max-Age=0", self.resolve(op["responses"]["200"])["headers"]["Set-Cookie"]["description"])

    def test_guest_cookie_is_documented(self):
        op = self.doc["paths"]["/api/v1/carts"]["post"]
        created = self.resolve(op["responses"]["201"])
        description = created["headers"]["Set-Cookie"]["description"]
        for attribute in ("cart_token", "HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1/carts", "Max-Age=2592000"):
            self.assertIn(attribute, description)

    def test_examples_validate(self):
        for path, item in self.doc["paths"].items():
            for method, op in item.items():
                if method == "parameters":
                    continue
                for status, response in op["responses"].items():
                    content = self.resolve(response).get("content", {}).get("application/json")
                    if not content:
                        continue
                    target, pointer = content["schema"]["$ref"].split("#")
                    schema = {"$ref": BASE + (target.removeprefix("./") or "cart.yaml") + "#" + pointer}
                    validator = Draft202012Validator(schema, registry=self.registry, format_checker=FormatChecker())
                    for name, example in content.get("examples", {}).items():
                        with self.subTest(op=f"{method} {path}", status=status, example=name):
                            self.assertEqual(list(validator.iter_errors(example["value"])), [])


if __name__ == "__main__":
    unittest.main()
