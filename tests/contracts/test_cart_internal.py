"""TASK:CART-03: internal snapshot/cleanup contract for order-service (spec sections 2-4)."""
from pathlib import Path
import unittest

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"
SNAPSHOT = "/internal/api/v1/carts/{cart_id}/snapshot"
CLEANUP = "/internal/api/v1/carts/{cart_id}/checkout-cleanup"


class CartInternalContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        docs = {name: yaml.safe_load((ROOT / "contracts/openapi" / name).read_text(encoding="utf-8"))
                for name in ("cart-internal.yaml", "common.yaml")}
        cls.doc = docs["cart-internal.yaml"]
        cls.registry = Registry().with_resources(
            (BASE + name, Resource.from_contents(d, default_specification=DRAFT202012)) for name, d in docs.items())

    def resolve(self, value):
        node = self.doc
        if "$ref" not in value:
            return value
        filename, pointer = value["$ref"].split("#")
        if filename not in ("", "./cart-internal.yaml"):
            node = yaml.safe_load((ROOT / "contracts/openapi" / filename.removeprefix("./")).read_text(encoding="utf-8"))
        for part in pointer.strip("/").split("/"):
            node = node[part]
        return node

    def validator(self, name):
        return Draft202012Validator({"$ref": BASE + "cart-internal.yaml#/components/schemas/" + name},
                                    registry=self.registry, format_checker=FormatChecker())

    def operations(self):
        for path, item in self.doc["paths"].items():
            for method, op in item.items():
                if method != "parameters":
                    yield path, method, op

    def test_two_operations_with_service_token_and_actor(self):
        self.assertEqual({(m, p) for p, m, _ in self.operations()}, {("get", SNAPSHOT), ("post", CLEANUP)})
        self.assertEqual(self.doc["security"], [{"serviceToken": []}])
        for path in (SNAPSHOT, CLEANUP):
            actor = next(self.resolve(p) for p in self.doc["paths"][path]["parameters"] if self.resolve(p)["name"] == "Cart-Actor")
            self.assertTrue(actor["required"])
            self.assertIn("guest:[0-9a-f]{64}", actor["schema"]["pattern"])

    def test_every_response_is_no_store_and_covers_auth(self):
        for path, method, op in self.operations():
            self.assertTrue({"401", "403", "404"} <= set(op["responses"]), path)
            for status, response in op["responses"].items():
                with self.subTest(op=f"{method} {path}", status=status):
                    headers = self.resolve(response)["headers"]
                    self.assertEqual(self.resolve(headers["Cache-Control"])["schema"]["const"], "no-store")

    def test_cleanup_key_and_request_bounds(self):
        op = self.doc["paths"][CLEANUP]["post"]
        key = next(p for p in op["parameters"] if p["name"] == "Idempotency-Key")
        self.assertTrue(key["required"])
        request = self.validator("CleanupRequest")
        item = {"item_id": "22222222-2222-4222-8222-222222222222", "item_version": 0, "quantity": 1}
        ok = {"order_id": "44444444-4444-4444-8444-444444444444", "items": [item]}
        self.assertEqual(list(request.iter_errors(ok)), [])
        for bad in ({**ok, "items": []}, {**ok, "items": [item] * 101}, {"items": [item]},
                    {**ok, "items": [{**item, "quantity": 0}]}, {**ok, "items": [{**item, "item_version": -1}]}):
            self.assertTrue(list(request.iter_errors(bad)), bad)

    def test_examples_validate(self):
        for path, method, op in self.operations():
            for status, response in op["responses"].items():
                content = self.resolve(response)["content"]["application/json"]
                target, pointer = content["schema"]["$ref"].split("#")
                schema = {"$ref": BASE + (target.removeprefix("./") or "cart-internal.yaml") + "#" + pointer}
                validator = Draft202012Validator(schema, registry=self.registry, format_checker=FormatChecker())
                for name, example in content.get("examples", {}).items():
                    with self.subTest(op=f"{method} {path}", status=status, example=name):
                        self.assertEqual(list(validator.iter_errors(example["value"])), [])


if __name__ == "__main__":
    unittest.main()
