"""TASK:CAT-01a/1b: admin security, version/key requirements and executable examples."""
from pathlib import Path
import unittest
from urllib.parse import urljoin

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"


class CatalogAdminContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = yaml.safe_load((ROOT / "contracts/openapi/catalog.yaml").read_text(encoding="utf-8"))
        cls.registry = Registry()
        for filename in ("catalog.yaml", "common.yaml"):
            doc = yaml.safe_load((ROOT / "contracts/openapi" / filename).read_text(encoding="utf-8"))
            cls.registry = cls.registry.with_resource(BASE + filename, Resource.from_contents(doc, default_specification=DRAFT202012))

    def resolve(self, value):
        if "$ref" not in value:
            return value
        result = self.doc
        for key in value["$ref"].removeprefix("#/").split("/"):
            result = result[key]
        return result

    def validator(self, name):
        return Draft202012Validator({"$ref": BASE + "catalog.yaml#/components/schemas/" + name},
                                   registry=self.registry, format_checker=FormatChecker())

    def test_admin_operations_require_bearer_and_mutation_guards(self):
        operations = [(method, op) for path, item in self.doc["paths"].items()
                      if path.startswith("/admin/") for method, op in item.items() if method != "parameters"]
        self.assertEqual(len(operations), 19)
        for method, op in operations:
            with self.subTest(operation=op["operationId"]):
                self.assertNotEqual(method, "delete")
                self.assertEqual(op["security"], [{"bearerAuth": []}])
                if method == "post":
                    self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])
                if method == "put" or op["operationId"] in ("publishProduct", "unpublishProduct"):
                    schema = op["requestBody"]["content"]["application/json"]["schema"]
                    schema = self.resolve(schema)
                    self.assertIn({"$ref": "./common.yaml#/components/schemas/ExpectedVersion"}, schema["allOf"])

    def test_money_is_integer_nonnegative_and_response_fields_are_closed(self):
        for name, field in (("AdminProduct", "base_price"), ("AdminVariant", "price_override")):
            schema = self.doc["components"]["schemas"][name]
            self.assertFalse(schema["additionalProperties"])
            prop = schema["properties"][field]
            self.assertEqual(prop["format"], "int64")
            validate = Draft202012Validator(prop)
            self.assertFalse(list(validate.iter_errors(9007199254740993)))
            self.assertTrue(list(validate.iter_errors(-1)))
            self.assertTrue(list(validate.iter_errors(1.5)))

    def test_local_catalog_receives_only_the_verifier_public_keys(self):
        compose = yaml.safe_load((ROOT / "infra/local/compose.yaml").read_text(encoding="utf-8"))
        env = compose["services"]["catalog"]["environment"]
        self.assertTrue(env.get("CATALOG_JWT_PUBLIC_KEYS", "").startswith("${USER_JWT_PUBLIC_KEYS:?"))
        self.assertNotIn("USER_JWT_PRIVATE_KEY", env)
        self.assertNotIn("CATALOG_JWT_PUBLIC_KEYS", compose["services"]["postgres"]["environment"])

    def test_sku_request_accepts_trimmed_input_and_response_keeps_legacy_identity(self):
        self.assertEqual(list(self.validator("VariantCreate").iter_errors(
            {"sku": " shirt-m ", "size": "M", "color": "Blue", "weight_grams": 100})), [])
        sku = self.doc["components"]["schemas"]["AdminVariant"]["properties"]["sku"]
        for value in ("SHIRT-M", "legacy-m"):
            self.assertEqual(list(Draft202012Validator(sku).iter_errors(value)), [])

    def test_catalog_01b_admin_reads_expose_edit_versions_and_authorization_errors(self):
        reads = (
            ("/admin/api/v1/catalog/collections/{id}", "AdminCollection"),
            ("/admin/api/v1/catalog/size-guides/{category_id}/{locale}", "AdminSizeGuide"),
        )
        for path, name in reads:
            with self.subTest(path=path):
                self.assertTrue(path in self.doc["paths"], f"missing admin read: {path}")
                op = self.doc["paths"][path]["get"]
                self.assertEqual(op["security"], [{"bearerAuth": []}])
                for status, code in (("401", "UNAUTHORIZED"), ("403", "FORBIDDEN"), ("404", "NOT_FOUND")):
                    response = self.resolve(op["responses"][status])
                    examples = response["content"]["application/json"]["examples"]
                    self.assertTrue(examples)
                    for example in examples.values():
                        self.assertEqual(example["value"]["code"], code)
                response = self.resolve(op["responses"]["200"])
                data = response["content"]["application/json"]["examples"]["success"]["value"]["data"]
                self.assertEqual(list(self.validator(name).iter_errors(data)), [])
                self.assertIn("version", data)
                self.assertTrue(list(self.validator(name).iter_errors({k: v for k, v in data.items() if k != "version"})))
                if name == "AdminCollection":
                    self.assertEqual(data["items"], sorted(data["items"], key=lambda item: (item["sort_order"], item["product_id"])))
                    statuses = self.doc["components"]["schemas"][name]["properties"]["status"]["enum"]
                    self.assertIn("DRAFT", statuses)
                    self.assertIn("INACTIVE", statuses)
                else:
                    self.assertGreaterEqual(data["version"], 1)
                    locale = next(self.resolve(p) for p in op["parameters"] if self.resolve(p)["name"] == "locale")
                    self.assertEqual(locale["schema"]["enum"], ["vi", "en"])
                    self.assertTrue(list(Draft202012Validator(locale["schema"]).iter_errors("fr")))
                    self.assertIn("400", op["responses"])
        self.assertNotIn("/api/v1/catalog/collections/{slug}", self.doc["paths"])
        self.assertNotIn("/api/v1/catalog/size-guides/{category_id}", self.doc["paths"])

    def test_collection_mutations_reject_media_and_bound_order_and_timestamps(self):
        self.assertTrue("/admin/api/v1/catalog/collections" in self.doc["paths"], "missing collection POST")
        create = self.doc["paths"]["/admin/api/v1/catalog/collections"]["post"]
        update = self.doc["paths"]["/admin/api/v1/catalog/collections/{id}"]["put"]
        key = {"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}
        self.assertIn(key, create["parameters"])
        self.assertNotIn(key, update["parameters"])
        product_id = "33333333-3333-4333-8333-333333333333"
        body = {"name_vi": "Mẫu", "name_en": "Sample", "slug": "sample",
                "items": [{"product_id": product_id, "sort_order": 0}],
                "start_at": "2026-10-07T12:00:00+07:00", "end_at": None}
        for name, value in (("CollectionCreate", body),
                            ("CollectionUpdate", {**body, "status": "ACTIVE", "expected_version": 0})):
            with self.subTest(schema=name):
                validator = self.validator(name)
                self.assertEqual(list(validator.iter_errors(value)), [])
                for forbidden in ("cover_url", "lookbook", "lookbook_images"):
                    self.assertTrue(list(validator.iter_errors({**value, forbidden: None})), forbidden)
                for bad_order in (-1, 2147483648, 0.5, "1"):
                    invalid = {**value, "items": [{"product_id": product_id, "sort_order": bad_order}]}
                    self.assertTrue(list(validator.iter_errors(invalid)), bad_order)
                self.assertEqual(list(validator.iter_errors({**value, "items": [
                    {"product_id": product_id, "sort_order": 2147483647},
                    {"product_id": "44444444-4444-4444-8444-444444444444", "sort_order": 2147483647}]})), [])
                self.assertTrue(list(validator.iter_errors({**value, "start_at": "2026-10-07T12:00:00"})))
                self.assertTrue(list(validator.iter_errors({**value, "items": value["items"] * 1001})))
        response = self.resolve(create["responses"]["400"])
        example = response["content"]["application/json"]["examples"]["missingProduct"]["value"]
        self.assertEqual(example["code"], "VALIDATION_ERROR")
        self.assertEqual(example["errors"][0]["field"], "items[0].product_id")
        success = self.resolve(create["responses"]["201"])["content"]["application/json"]["examples"]["success"]["value"]["data"]
        self.assertEqual((success["status"], success["version"], success["cover_url"]), ("DRAFT", 0, None))

    def test_size_guide_put_requires_key_version_and_allows_optional_guideline(self):
        path = self.doc["paths"]["/admin/api/v1/catalog/size-guides/{category_id}/{locale}"]
        self.assertTrue("put" in path, "missing size guide PUT contract")
        op = path["put"]
        self.assertIn({"$ref": "./common.yaml#/components/parameters/IdempotencyKey"}, op["parameters"])
        self.assertIn("201", op["responses"])
        self.assertIn("200", op["responses"])
        self.assertIn("409", op["responses"])
        body = {"expected_version": 0, "table_json": {"columns": ["Size"], "rows": [["M"]]}}
        validator = self.validator("SizeGuidePut")
        for guideline in ({}, {"guideline_html": None}, {"guideline_html": ""}, {"guideline_html": "<p>Sample</p>"}):
            self.assertEqual(list(validator.iter_errors({**body, **guideline})), [])
        self.assertTrue(list(validator.iter_errors({"table_json": body["table_json"]})))
        self.assertTrue(list(validator.iter_errors({**body, "table_json": {**body["table_json"], "unknown": 1}})))
        content = self.doc["components"]["schemas"]["AdminSizeGuide"]["properties"]["guideline_html"]
        self.assertEqual(content["maxLength"], 20000)
        self.assertTrue(list(Draft202012Validator(content).iter_errors("x" * 20001)))

    def test_every_admin_operation_has_valid_success_and_error_examples(self):
        count = 0
        for path, item in self.doc["paths"].items():
            if not path.startswith("/admin/"):
                continue
            for method, op in item.items():
                if method == "parameters":
                    continue
                count += 1
                examples = {"2": 0, "4": 0}
                for status, response in op["responses"].items():
                    response = self.resolve(response)
                    media = response["content"]["application/json"]
                    schema = {"$ref": urljoin(BASE + "catalog.yaml", media["schema"]["$ref"])}
                    validator = Draft202012Validator(schema, registry=self.registry, format_checker=FormatChecker())
                    for example in media.get("examples", {}).values():
                        with self.subTest(operation=op["operationId"], status=status):
                            self.assertEqual([e.message for e in validator.iter_errors(example["value"])], [])
                        if status[0] in examples:
                            examples[status[0]] += 1
                self.assertGreater(examples["2"], 0)
                self.assertGreater(examples["4"], 0)
        self.assertEqual(count, 19)
