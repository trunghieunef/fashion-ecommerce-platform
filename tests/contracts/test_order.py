"""TASK:ORD-01: customer order contract (spec sections 5-8) and the cart checkout token; TASK:ORD-03: OPS admin orders; TASK:ORD-04: cancel and returns."""
from pathlib import Path
import unittest

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"
FILES = ("order.yaml", "cart.yaml", "common.yaml")
OPERATIONS = {("post", "/api/v1/orders/quote"), ("post", "/api/v1/orders"), ("get", "/api/v1/orders/mine"),
              ("get", "/api/v1/orders/{order_no}"), ("post", "/api/v1/orders/{order_no}/cancel")}
ADMIN = {("get", "/admin/api/v1/orders"), ("get", "/admin/api/v1/orders/{order_no}"),
         ("post", "/admin/api/v1/orders/{order_no}/confirm-cod"), ("post", "/admin/api/v1/orders/{order_no}/cancel"),
         ("post", "/admin/api/v1/orders/{order_no}/returns"), ("post", "/admin/api/v1/orders/{order_no}/returns/{return_id}/void")}
QUOTE = {"cart_id": "11111111-1111-4111-8111-111111111111", "cart_version": 4, "method": "COD",
         "shipping_address": {"recipient_name": "Synthetic Person", "phone": "0912345678", "province_code": "79",
                              "ward_code": "26734", "address_line": "1 Synthetic St"},
         "contact": {"phone": "0912345678", "email": None}, "note": None}


class OrderContractTest(unittest.TestCase):
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

    def validator(self, ref):
        return Draft202012Validator({"$ref": ref}, registry=self.registry, format_checker=FormatChecker())

    def operations(self):
        for path, item in self.docs["order.yaml"]["paths"].items():
            for method, op in item.items():
                yield method, path, op

    def test_operations_and_credentials(self):
        self.assertEqual({(m, p) for m, p, _ in self.operations()}, OPERATIONS | ADMIN)
        ops = {(m, p): op for m, p, op in self.operations()}
        self.assertEqual(ops[("post", "/api/v1/orders")]["security"], [{"memberBearer": []}, {"checkoutToken": []}])
        self.assertEqual(ops[("get", "/api/v1/orders/mine")]["security"], [{"memberBearer": []}])
        self.assertEqual(ops[("get", "/api/v1/orders/{order_no}")]["security"], [{"memberBearer": []}, {"orderAccess": []}])
        self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, ops[("post", "/api/v1/orders")]["parameters"])
        self.assertEqual(set(ops[("post", "/api/v1/orders")]["responses"]), {"202", "400", "401", "404", "409", "503"})

    def test_every_response_is_no_store_and_examples_validate(self):
        for method, path, op in self.operations():
            for status, response in op["responses"].items():
                with self.subTest(op=f"{method} {path}", status=status):
                    response = self.resolve(response, "order.yaml")
                    self.assertEqual(self.resolve(response["headers"]["Cache-Control"], "order.yaml")["schema"]["const"], "no-store")
                    content = response["content"]["application/json"]
                    target, pointer = content["schema"]["$ref"].split("#")
                    validator = self.validator(BASE + (target.removeprefix("./") or "order.yaml") + "#" + pointer)
                    for example in content.get("examples", {}).values():
                        self.assertEqual([e.message for e in validator.iter_errors(example["value"])], [])

    def test_request_bodies_are_strict_and_never_carry_amounts(self):
        quote = self.validator(BASE + "order.yaml#/components/schemas/QuoteRequest")
        self.assertEqual(list(quote.iter_errors(QUOTE)), [])
        for bad in ({**QUOTE, "total_amount": 1}, {**QUOTE, "method": "CASH"}, {**QUOTE, "cart_version": -1},
                    {**QUOTE, "shipping_address": {**QUOTE["shipping_address"], "phone": "12ab"}},
                    {**QUOTE, "note": "x" * 501}):
            self.assertTrue(list(quote.iter_errors(bad)), bad)
        create = self.validator(BASE + "order.yaml#/components/schemas/CreateOrderRequest")
        ok = {"cart_id": QUOTE["cart_id"], "cart_version": 4, "quote_token": "a.b"}
        self.assertEqual(list(create.iter_errors(ok)), [])
        for bad in ({**ok, "total_amount": 1}, {"cart_id": QUOTE["cart_id"], "cart_version": 4}):
            self.assertTrue(list(create.iter_errors(bad)), bad)

    def test_admin_operations(self):
        ops = {(m, p): op for m, p, op in self.operations()}
        for key in ADMIN:
            self.assertEqual(ops[key]["security"], [{"bearerAuth": []}], key)
            self.assertIn("403", ops[key]["responses"], key)
        for action in ("confirm-cod", "cancel", "returns"):
            op = ops[("post", f"/admin/api/v1/orders/{{order_no}}/{action}")]
            self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])
            self.assertEqual(set(op["responses"]), {"202", "400", "401", "403", "404", "409"})
        void = ops[("post", "/admin/api/v1/orders/{order_no}/returns/{return_id}/void")]
        self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, void["parameters"])
        self.assertEqual(set(void["responses"]), {"202", "400", "401", "403", "404", "409"})
        body = self.validator(BASE + "order.yaml#/components/schemas/VoidReturnRequest")
        self.assertEqual(list(body.iter_errors({"expected_version": 3, "reason": "Kho kiem lai"})), [])
        for bad in ({"expected_version": 3}, {"expected_version": 3, "reason": " "}, {"expected_version": 3, "reason": "x", "extra": 1}):
            self.assertTrue(list(body.iter_errors(bad)), bad)

    def test_admin_command_bodies(self):
        confirm = self.validator(BASE + "order.yaml#/components/schemas/ConfirmCodRequest")
        for ok in ({"expected_version": 2}, {"expected_version": 2, "note": "Khach dong y"}, {"expected_version": 0, "note": None}):
            self.assertEqual(list(confirm.iter_errors(ok)), [], ok)
        for bad in ({}, {"expected_version": -1}, {"expected_version": 2, "note": "x" * 501}, {"expected_version": "2"}):
            self.assertTrue(list(confirm.iter_errors(bad)), bad)
        cancel = self.validator(BASE + "order.yaml#/components/schemas/CancelOrderRequest")
        self.assertEqual(list(cancel.iter_errors({"expected_version": 2, "reason": "Khach khong nghe may"})), [])
        for bad in ({"expected_version": 2}, {"expected_version": 2, "reason": ""}, {"expected_version": 2, "reason": "   "},
                    {"expected_version": 2, "reason": "x" * 501}):
            self.assertTrue(list(cancel.iter_errors(bad)), bad)

    def test_ops_cancelled_reason_and_redacted_command_order(self):
        order = self.docs["order.yaml"]["components"]["schemas"]["Order"]["properties"]
        self.assertIn("OPS_CANCELLED", order["reason_code"]["description"])
        command = self.validator(BASE + "order.yaml#/components/schemas/CommandOrder")
        op = self.docs["order.yaml"]["paths"]["/admin/api/v1/orders/{order_no}/confirm-cod"]["post"]
        full = op["responses"]["202"]["content"]["application/json"]["examples"]["confirmed"]["value"]["data"]
        redacted = {k: v for k, v in full.items() if k not in ("contact", "shipping_address", "note")}
        self.assertEqual(list(command.iter_errors(redacted)), [])
        self.assertTrue(list(command.iter_errors({**redacted, "contact_phone": "0912345678"})))
        detail = self.validator(BASE + "order.yaml#/components/schemas/AdminOrderDetail")
        get = self.docs["order.yaml"]["paths"]["/admin/api/v1/orders/{order_no}"]["get"]
        data = get["responses"]["200"]["content"]["application/json"]["examples"]["packing"]["value"]["data"]
        finance = {k: v for k, v in data.items() if k not in ("contact_phone", "contact_email", "shipping_address", "note")}
        self.assertEqual(list(detail.iter_errors(finance)), [])

    def test_customer_cancel_operation_and_body(self):
        op = self.docs["order.yaml"]["paths"]["/api/v1/orders/{order_no}/cancel"]["post"]
        self.assertEqual(op["security"], [{"memberBearer": []}, {"orderAccess": []}])
        self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])
        self.assertEqual(set(op["responses"]), {"202", "400", "401", "404", "409"})
        cancel = self.validator(BASE + "order.yaml#/components/schemas/CustomerCancelRequest")
        for ok in ({"expected_version": 3}, {"expected_version": 3, "reason": "Doi y"}, {"expected_version": 0, "reason": None}):
            self.assertEqual(list(cancel.iter_errors(ok)), [], ok)
        for bad in ({}, {"expected_version": -1}, {"expected_version": 3, "reason": "x" * 501}, {"expected_version": 3, "extra": 1}):
            self.assertTrue(list(cancel.iter_errors(bad)), bad)

    def test_return_slip_request_is_strict(self):
        item = {"order_item_id": "77777777-7777-4777-8777-777777777777", "received_quantity": 2, "restock_quantity": 1}
        ok = {"expected_version": 7, "reason": "Khach tu choi nhan", "evidence_ref": "phieu-nhap-001", "items": [item]}
        request = self.validator(BASE + "order.yaml#/components/schemas/ReturnRequest")
        self.assertEqual(list(request.iter_errors(ok)), [])
        self.assertEqual(list(request.iter_errors({**ok, "items": [{**item, "restock_quantity": 0, "condition": None}]})), [])
        for bad in ({k: v for k, v in ok.items() if k != "evidence_ref"}, {**ok, "reason": "  "}, {**ok, "items": []},
                    {**ok, "items": [{**item, "received_quantity": 0}]}, {**ok, "items": [{**item, "restock_quantity": -1}]},
                    {**ok, "items": [{**item, "order_item_id": "x"}]}, {**ok, "items": [{**item, "condition": "x" * 201}]},
                    {**ok, "items": [{**item, "sku": "TEE-M"}]}, {**ok, "total_amount": 1}):
            self.assertTrue(list(request.iter_errors(bad)), bad)

    def test_return_conflict_and_statuses_are_documented(self):
        order = self.docs["order.yaml"]["components"]["schemas"]["Order"]["properties"]
        self.assertIn("CUSTOMER_CANCELLED", order["reason_code"]["description"])
        for word in ("RETURNING READY", "RETURNED"):
            self.assertIn(word, order["processing_status"]["description"])
        self.assertEqual(order["allowed_actions"]["items"]["enum"], ["CANCEL"])
        conflict = self.docs["order.yaml"]["components"]["responses"]["CommandConflict"]
        self.assertIn("RETURN_EXCEEDS_ORDERED", conflict["description"])
        exceeded = conflict["content"]["application/json"]["examples"]["exceeded"]["value"]
        self.assertEqual(list(self.validator("https://fashion.local/contracts/openapi/common.yaml#/components/schemas/ApiError").iter_errors(exceeded)), [])
        self.assertEqual(exceeded["errors"][0]["message"], "ordered=2 received=1 requested=2")

    def test_admin_detail_carries_returns_and_wait_return(self):
        detail = self.validator(BASE + "order.yaml#/components/schemas/AdminOrderDetail")
        get = self.docs["order.yaml"]["paths"]["/admin/api/v1/orders/{order_no}"]["get"]
        data = get["responses"]["200"]["content"]["application/json"]["examples"]["returning"]["value"]["data"]
        self.assertEqual(list(detail.iter_errors(data)), [])
        self.assertEqual(data["saga"]["step"], "WAIT_RETURN")
        self.assertTrue(list(detail.iter_errors({k: v for k, v in data.items() if k != "returns"})))
        self.assertTrue(list(detail.iter_errors({**data, "saga": {**data["saga"], "shipment_state": "HANDED_OVER"}})))
        slip = self.validator(BASE + "order.yaml#/components/schemas/ReturnSlip")
        self.assertEqual(list(slip.iter_errors({**data["returns"][0], "reason": None})), [])
        self.assertTrue(list(slip.iter_errors({**data["returns"][0], "status": "DONE"})))
        self.assertEqual(list(slip.iter_errors({**data["returns"][0], "status": "VOIDED", "evidence_ref": None})), [])

    def test_cart_checkout_token_operation(self):
        op = self.docs["cart.yaml"]["paths"]["/api/v1/carts/{cart_id}/checkout-token"]["post"]
        self.assertEqual(op["security"], [{"guestCookie": []}])
        self.assertEqual(set(op["responses"]), {"200", "400", "401", "404"})
        example = op["responses"]["200"]["content"]["application/json"]["examples"]["issued"]["value"]
        validator = self.validator(BASE + "cart.yaml#/components/schemas/CheckoutTokenResponse")
        self.assertEqual(list(validator.iter_errors(example)), [])


if __name__ == "__main__":
    unittest.main()
