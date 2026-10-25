"""TASK:PAY-01: payment admin and internal contracts (spec section 5)."""
from pathlib import Path
import unittest

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"
FILES = ("payment.yaml", "payment-internal.yaml", "common.yaml")
ADMIN = {("get", "/admin/api/v1/payments"), ("get", "/admin/api/v1/payments/{id}"),
         ("post", "/admin/api/v1/payments/{id}/cod-settlements")}
# TASK:PAY-02: the only public payment paths (no auth: the IPN is verified by its signature).
VNPAY = {("get", "/api/v1/payments/vnpay/ipn"), ("get", "/api/v1/payments/vnpay/return")}
INTERNAL = {("post", "/internal/api/v1/payments"), ("get", "/internal/api/v1/payments/by-order/{order_id}"),
            ("post", "/internal/api/v1/payments/by-order/{order_id}/close")}
SNAPSHOT = {"order_id": "99999999-9999-4999-8999-999999999991", "order_no": "ORD1", "user_id": None, "method": "COD",
            "amount": 275000, "currency": "VND", "expires_at": "2026-10-20T06:00:00Z"}


class PaymentContractTest(unittest.TestCase):
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
                yield method, path, op

    def validator(self, ref):
        return Draft202012Validator({"$ref": ref}, registry=self.registry, format_checker=FormatChecker())

    def test_operations(self):
        self.assertEqual({(m, p) for m, p, _ in self.operations("payment.yaml")}, ADMIN | VNPAY)
        self.assertEqual({(m, p) for m, p, _ in self.operations("payment-internal.yaml")}, INTERNAL)
        self.assertEqual(self.docs["payment-internal.yaml"]["security"], [{"serviceToken": []}])
        for m, p, op in self.operations("payment.yaml"):
            self.assertEqual(op["security"], [] if (m, p) in VNPAY else [{"bearerAuth": []}])

    def test_vnpay_ack(self):
        ack = self.validator(BASE + "payment.yaml#/components/schemas/VnPayIpnAck")
        self.assertEqual(list(ack.iter_errors({"RspCode": "99", "Message": "Unknow error"})), [])
        for bad in ({"RspCode": "00"}, {"RspCode": "05", "Message": "x"}, {"RspCode": "00", "Message": "Confirm Success", "data": 1}):
            self.assertTrue(list(ack.iter_errors(bad)), bad)

    def test_every_response_is_no_store_and_examples_validate(self):
        for name in ("payment.yaml", "payment-internal.yaml"):
            for method, path, op in self.operations(name):
                for status, response in op["responses"].items():
                    with self.subTest(file=name, op=f"{method} {path}", status=status):
                        response = self.resolve(response, name)
                        self.assertEqual(self.resolve(response["headers"]["Cache-Control"], name)["schema"]["const"], "no-store")
                        content = response.get("content", {}).get("application/json")
                        if not content:
                            continue
                        target, pointer = content["schema"]["$ref"].split("#")
                        validator = self.validator(BASE + (target.removeprefix("./") or name) + "#" + pointer)
                        for example in content.get("examples", {}).values():
                            self.assertEqual([e.message for e in validator.iter_errors(example["value"])], [])

    def test_snapshot_and_close_bodies(self):
        snapshot = self.validator(BASE + "payment-internal.yaml#/components/schemas/OrderSnapshot")
        self.assertEqual(list(snapshot.iter_errors(SNAPSHOT)), [])
        for bad in ({**SNAPSHOT, "currency": "USD"}, {**SNAPSHOT, "method": "CARD"}, {**SNAPSHOT, "amount": "275000"},
                    {**SNAPSHOT, "amount": 1000000000001}, {**SNAPSHOT, "payment_url": "x"}):
            self.assertTrue(list(snapshot.iter_errors(bad)), bad)
        close = self.validator(BASE + "payment-internal.yaml#/components/schemas/PaymentClose")
        self.assertTrue(list(close.iter_errors(SNAPSHOT)), "reason is required")
        self.assertEqual(list(close.iter_errors({**SNAPSHOT, "reason": "khách hủy"})), [])
        op = self.docs["payment-internal.yaml"]["paths"]["/internal/api/v1/payments/by-order/{order_id}/close"]["post"]
        self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])

    def test_settlement_body(self):
        op = self.docs["payment.yaml"]["paths"]["/admin/api/v1/payments/{id}/cod-settlements"]["post"]
        self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])
        body = self.validator(BASE + "payment.yaml#/components/schemas/CodSettlement")
        ok = {"settlement_ref": "SAO-KE-1", "gross_amount": 100000, "fee_amount": 3000, "net_amount": 97000,
              "evidence_ref": "SAO-KE-1.pdf", "expected_version": 1}
        self.assertEqual(list(body.iter_errors(ok)), [])
        for bad in ({**ok, "gross_amount": 1.5}, {**ok, "fee_amount": -1}, {**ok, "settlement_ref": ""}, {**ok, "extra": 1}):
            self.assertTrue(list(body.iter_errors(bad)), bad)

    def test_statuses_match_05(self):
        self.assertEqual(self.docs["payment.yaml"]["components"]["schemas"]["PaymentStatus"]["enum"],
                         ["CREATED", "PROCESSING", "UNKNOWN", "SUCCESS", "FAILED", "PARTIALLY_REFUNDED", "REFUNDED",
                          "COD_PENDING", "COD_COLLECTED"])


if __name__ == "__main__":
    unittest.main()
