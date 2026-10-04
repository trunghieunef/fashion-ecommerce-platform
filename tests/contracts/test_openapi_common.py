"""TASK:PLT-02: shared HTTP conventions from 03 sections 1.2/1.3 in contracts/openapi/common.yaml."""
from pathlib import Path
import unittest

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
COMMON_ID = "https://fashion.local/contracts/openapi/common.yaml"

# 03 section 1.2 error table, copied by hand so a dropped code is caught.
ERROR_CODES = [
    "VALIDATION_ERROR", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "CONFLICT", "VERSION_CONFLICT",
    "PRICE_CHANGED", "QUOTE_EXPIRED", "OUT_OF_STOCK", "PROMOTION_LIMIT", "ALREADY_REVIEWED",
    "VOUCHER_INVALID", "PAYMENT_AMOUNT_UNSUPPORTED", "ACCOUNT_LOCKED", "RATE_LIMITED",
    "TEMPORARILY_UNAVAILABLE", "INTERNAL",
]
METADATA = {"request_id": "4c8a1f2e-0000-4000-8000-000000000001", "trace_id": "4c8a1f2e-0000-4000-8000-000000000002"}


class OpenApiCommonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        doc = yaml.safe_load((ROOT / "contracts/openapi/common.yaml").read_text(encoding="utf-8"))
        cls.doc = doc
        cls.registry = Registry().with_resource(COMMON_ID, Resource.from_contents(doc, default_specification=DRAFT202012))

    def validator(self, pointer):
        return Draft202012Validator({"$ref": COMMON_ID + "#" + pointer}, registry=self.registry, format_checker=FormatChecker())

    def assert_valid(self, pointer, value):
        self.assertEqual([e.message for e in self.validator(pointer).iter_errors(value)], [])

    def assert_rejected(self, pointer, value, keyword):
        errors = list(self.validator(pointer).iter_errors(value))
        self.assertTrue(any(e.validator == keyword for e in errors), [e.message for e in errors])

    def test_api_error_accepts_every_code_in_03(self):
        for code in ERROR_CODES:
            with self.subTest(code=code):
                self.assert_valid("/components/schemas/ApiError", {"code": code, "message": "safe", "metadata": METADATA})

    def test_api_error_rejects_unknown_code(self):
        self.assert_rejected("/components/schemas/ApiError", {"code": "OOPS", "message": "x", "metadata": METADATA}, "enum")

    def test_api_error_never_carries_stack_trace(self):
        body = {"code": "INTERNAL", "message": "x", "metadata": METADATA, "stack_trace": "at Foo.bar"}
        self.assert_rejected("/components/schemas/ApiError", body, "additionalProperties")

    def test_api_error_requires_request_id_for_support(self):
        body = {"code": "INTERNAL", "message": "x", "metadata": {"trace_id": "t"}}
        self.assert_rejected("/components/schemas/ApiError", body, "required")

    def test_field_errors_name_the_field(self):
        body = {"code": "VALIDATION_ERROR", "message": "x", "metadata": METADATA, "errors": [{"message": "bad"}]}
        self.assert_rejected("/components/schemas/ApiError", body, "required")

    def test_accepted_response_points_to_status(self):
        pointer = "/components/schemas/AcceptedResponse"
        ok = {"code": "OK", "data": {"processing_status": "PROCESSING", "status_url": "/api/v1/orders/FS-1"}, "metadata": METADATA}
        self.assert_valid(pointer, ok)
        missing = {"code": "OK", "data": {"processing_status": "PROCESSING"}, "metadata": METADATA}
        self.assert_rejected(pointer, missing, "required")

    def test_public_limit_is_1_to_100_default_20(self):
        schema = self.doc["components"]["parameters"]["Limit"]["schema"]
        self.assertEqual(schema.get("default"), 20)
        pointer = "/components/parameters/Limit/schema"
        for good in (1, 100):
            self.assert_valid(pointer, good)
        self.assert_rejected(pointer, 0, "minimum")
        self.assert_rejected(pointer, 101, "maximum")

    def test_idempotency_key_fits_05_varchar_128(self):
        pointer = "/components/parameters/IdempotencyKey/schema"
        self.assertTrue(self.doc["components"]["parameters"]["IdempotencyKey"]["required"])
        self.assert_valid(pointer, "k" * 128)
        self.assert_rejected(pointer, "", "minLength")
        self.assert_rejected(pointer, "k" * 129, "maxLength")

    def test_expected_version_is_required_non_negative_integer(self):
        pointer = "/components/schemas/ExpectedVersion"
        self.assert_valid(pointer, {"expected_version": 0})
        self.assert_rejected(pointer, {}, "required")
        self.assert_rejected(pointer, {"expected_version": -1}, "minimum")


if __name__ == "__main__":
    unittest.main()
