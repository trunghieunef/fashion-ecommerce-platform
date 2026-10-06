"""TASK:CAT-01a: admin security, version/key requirements and executable examples."""
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
        self.assertEqual(len(operations), 14)
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
        self.assertEqual(count, 14)
