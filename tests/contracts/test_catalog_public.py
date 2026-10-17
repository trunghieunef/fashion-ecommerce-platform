"""TASK:CAT-02a: executable public browse and internal quote wire contract."""
from pathlib import Path
import unittest
from urllib.parse import urljoin

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"
OPERATIONS = {
    "listProducts": ("/api/v1/catalog/products", "get"),
    "searchProducts": ("/api/v1/catalog/search", "get"),
    "getProduct": ("/api/v1/catalog/products/{id}", "get"),
    "listCategories": ("/api/v1/catalog/categories", "get"),
    "listBrands": ("/api/v1/catalog/brands", "get"),
    "listCollections": ("/api/v1/catalog/collections", "get"),
    "getCollection": ("/api/v1/catalog/collections/{slug}", "get"),
    "getSizeGuide": ("/api/v1/catalog/size-guides/{category_id}", "get"),
    "quoteVariants": ("/internal/api/v1/catalog/variants/quote", "post"),
}


class CatalogPublicContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docs = {name: yaml.safe_load((ROOT / "contracts/openapi" / name).read_text(encoding="utf-8"))
                    for name in ("catalog.yaml", "common.yaml")}
        cls.doc = cls.docs["catalog.yaml"]
        cls.registry = Registry().with_resources(
            (BASE + name, Resource.from_contents(doc, default_specification=DRAFT202012))
            for name, doc in cls.docs.items())

    def resolve(self, value):
        if "$ref" not in value:
            return value
        filename, pointer = value["$ref"].split("#")
        result = self.docs[filename.removeprefix("./") or "catalog.yaml"]
        for part in pointer.strip("/").split("/"):
            result = result[part]
        return result

    def operation(self, name):
        path, method = OPERATIONS[name]
        self.assertIn(path, self.doc["paths"], f"missing {name}: {path}")
        op = self.doc["paths"][path][method]
        self.assertEqual(op["operationId"], name)
        self.assertIn("description", op, f"missing implementation description: {name}")
        return op

    def validator(self, name):
        return Draft202012Validator({"$ref": BASE + "catalog.yaml#/components/schemas/" + name},
                                   registry=self.registry, format_checker=FormatChecker())

    def test_all_nine_operations_and_security_boundaries(self):
        for name, (path, _) in OPERATIONS.items():
            with self.subTest(operation=name):
                op = self.operation(name)
                self.assertIn("CAT-02a implemented", op["description"])
                self.assertEqual(op["security"], [{"serviceToken": []}] if path.startswith("/internal/") else [])
        quote = self.operation("quoteVariants")
        for status, code, message in (("401", "UNAUTHORIZED", "INVALID_SERVICE_TOKEN"),
                                      ("403", "FORBIDDEN", "CALLER_NOT_ALLOWED")):
            response = self.resolve(quote["responses"][status])
            values = [e["value"] for e in response["content"]["application/json"]["examples"].values()]
            self.assertTrue(any(e["code"] == code and e["message"] == message for e in values))
        for response in quote["responses"].values():
            self.assertEqual(self.resolve(response)["headers"]["Cache-Control"]["schema"]["const"], "no-store")

    def test_public_reads_declare_cache_headers(self):
        # TASK:CAT-02b spec section 6: success max-age per route, every inline error no-store.
        expected = {"listProducts": 30, "searchProducts": 30, "getProduct": 5, "listCategories": 30,
                    "listBrands": 30, "listCollections": 30, "getCollection": 60, "getSizeGuide": 60}
        for name, seconds in expected.items():
            with self.subTest(operation=name):
                responses = self.operation(name)["responses"]
                headers = self.resolve(responses["200"])["headers"]
                self.assertEqual(headers["Cache-Control"]["schema"]["const"], f"public, max-age={seconds}")
                if name in ("getProduct", "getSizeGuide"):
                    self.assertEqual(headers["Vary"]["schema"]["const"], "Accept-Language")
                for status, response in responses.items():
                    if status != "200" and "$ref" not in response:
                        self.assertEqual(response["headers"]["Cache-Control"]["schema"]["const"], "no-store", status)

    def test_detail_reports_stock_per_variant(self):
        validator = self.validator("PublicVariant")
        variant = {"id": "11111111-1111-4111-8111-111111111111", "sku": "TEE-M", "size": "M", "color": "Blue",
                   "effective_price": 100000, "available": 3, "observed_at": "2026-10-17T03:00:00.000Z"}
        self.assertEqual(list(validator.iter_errors(variant)), [])
        for field, value in (("available", -1), ("available", True), ("observed_at", "yesterday")):
            with self.subTest(field=field, value=value):
                self.assertTrue(list(validator.iter_errors({**variant, field: value})))
        unknown = {**variant, "available": None, "observed_at": None}
        self.assertEqual(list(validator.iter_errors(unknown)), [])
        missing = {k: v for k, v in variant.items() if k != "available"}
        self.assertTrue(list(validator.iter_errors(missing)))
        detail = self.doc["components"]["schemas"]["PublicProductDetail"]["properties"]
        self.assertTrue(list(Draft202012Validator(detail["available"]).iter_errors(-1)))
        self.assertEqual(list(Draft202012Validator(detail["available"]).iter_errors(5)), [])

    def test_browse_filter_domains_and_search_requirement(self):
        expected = {"q", "category_id", "brand_id", "size", "color", "tag", "min_price", "max_price", "sort", "limit", "cursor"}
        for name in ("listProducts", "searchProducts"):
            params = {p["name"]: p for p in map(self.resolve, self.operation(name)["parameters"])}
            self.assertEqual(set(params), expected)
            self.assertEqual(params["q"].get("required", False), name == "searchProducts")
            for field, maximum in (("q", 100), ("size", 64), ("color", 64), ("tag", 64)):
                schema = params[field]["schema"]
                self.assertEqual((schema["minLength"], schema["maxLength"]), (1, maximum))
            self.assertEqual(params["sort"]["schema"]["enum"], ["NEWEST", "BEST_SELLING", "PRICE_ASC", "PRICE_DESC"])
            self.assertEqual(params["sort"]["schema"]["default"], "NEWEST")
            for field in ("min_price", "max_price"):
                schema = params[field]["schema"]
                self.assertEqual((schema["type"], schema["minimum"], schema["maximum"]), ("integer", 0, 9223372036854775807))
        for name in ("listProducts", "searchProducts", "listCollections", "getCollection"):
            params = {p["name"]: p for p in map(self.resolve, self.operation(name)["parameters"])}
            self.assertEqual((params["limit"]["schema"]["minimum"], params["limit"]["schema"]["maximum"],
                              params["limit"]["schema"]["default"]), (1, 100, 20))
            self.assertIn("INVALID_CURSOR", params["cursor"]["description"])
        locale = next(p for p in map(self.resolve, self.operation("getSizeGuide")["parameters"]) if p["name"] == "locale")
        self.assertEqual(locale["schema"]["enum"], ["vi", "en"])

    def test_public_money_and_closed_shapes(self):
        for name, fields in (("ProductSummary", ("min_price", "max_price")),
                             ("PublicProductDetail", ("min_price", "max_price")),
                             ("PublicVariant", ("effective_price",)),
                             ("VariantQuoteFound", ("base_price", "price_override", "effective_price"))):
            schema = self.doc["components"]["schemas"].get(name)
            self.assertIsNotNone(schema, name)
            self.assertFalse(schema["additionalProperties"])
            for field in fields:
                self.assertIn(field, schema["properties"], f"missing money field: {name}.{field}")
                money = schema["properties"][field]
                self.assertEqual((money["type"], money["format"], money["minimum"]), ("integer", "int64", 0))
                validator = Draft202012Validator(money)
                self.assertEqual(list(validator.iter_errors(9007199254740993)), [])
                for invalid in (-1, 0.5, None, 9223372036854775808):
                    self.assertTrue(list(validator.iter_errors(invalid)))
        lookbook = self.doc["components"]["schemas"]["PublicLookbookImage"]
        self.assertEqual(set(lookbook["properties"]), {"asset_id", "caption_vi", "caption_en", "sort_order"})
        self.assertFalse(lookbook["additionalProperties"])
        for name, fields in (("PublicProductDetail", ("brand", "size_guide", "available", "observed_at")),
                             ("ProductSummary", ("image",)),
                             ("PublicCollection", ("cover_asset_id", "start_at", "end_at")),
                             ("PublicProductPage", ("next_cursor",))):
            schema = self.doc["components"]["schemas"][name]
            for field in fields:
                self.assertIn(field, schema["required"])
                validator = Draft202012Validator(
                    {"$ref": BASE + "catalog.yaml#/components/schemas/" + name + "/properties/" + field},
                    registry=self.registry)
                self.assertEqual(list(validator.iter_errors(None)), [], (name, field))
        for name, fields in (("PublicProductDetail", ("description_vi", "description_en")),
                             ("PublicCardImage", ("alt_vi", "alt_en")),
                             ("PublicLookbookImage", ("caption_vi", "caption_en")),
                             ("PublicSizeGuide", ("guideline_html",))):
            for field in fields:
                validator = Draft202012Validator(self.doc["components"]["schemas"][name]["properties"][field])
                self.assertTrue(list(validator.iter_errors(None)), (name, field))

    def test_quote_request_and_non_null_record_shapes(self):
        self.operation("quoteVariants")
        validator = self.validator("VariantQuoteRequest")
        uid = "11111111-1111-4111-8111-111111111111"
        self.assertEqual(list(validator.iter_errors({"variant_ids": [uid]})), [])
        for body in ({}, {"variant_ids": []}, {"variant_ids": [uid, uid]}, {"variant_ids": ["bad"]},
                     {"variant_ids": [uid] * 101}, {"variant_ids": [uid], "amount": 1}):
            self.assertTrue(list(validator.iter_errors(body)))
        validator = self.validator("VariantQuoteItem")
        self.assertEqual(list(validator.iter_errors({"variant_id": uid, "found": False})), [])
        self.assertTrue(list(validator.iter_errors({"variant_id": uid, "found": False, "active": False})))
        found = {"variant_id": uid, "found": True, "active": True, "sku": "TEE-M", "product_id": uid,
                 "name_vi": "Áo", "name_en": "Tee", "size": "M", "color": "Blue", "base_price": 100,
                 "effective_price": 100, "weight_grams": 100, "product_version": 0}
        self.assertEqual(list(validator.iter_errors(found)), [])
        self.assertEqual(list(validator.iter_errors({**found, "price_override": 90})), [])
        self.assertTrue(list(validator.iter_errors({**found, "price_override": None})))

    def test_all_operation_examples_validate_success_and_errors(self):
        for name in OPERATIONS:
            op = self.operation(name)
            groups = {"2": 0, "4": 0}
            for status, response in op["responses"].items():
                media = self.resolve(response)["content"]["application/json"]
                validator = Draft202012Validator({"$ref": urljoin(BASE + "catalog.yaml", media["schema"]["$ref"])},
                                               registry=self.registry, format_checker=FormatChecker())
                for example in media.get("examples", {}).values():
                    with self.subTest(operation=name, status=status):
                        self.assertEqual([e.message for e in validator.iter_errors(example["value"])], [])
                    if status[0] in groups:
                        groups[status[0]] += 1
            self.assertGreater(groups["2"], 0, name)
            self.assertGreater(groups["4"], 0, name)
            if "requestBody" in op:
                media = op["requestBody"]["content"]["application/json"]
                validator = Draft202012Validator({"$ref": urljoin(BASE + "catalog.yaml", media["schema"]["$ref"])},
                                               registry=self.registry, format_checker=FormatChecker())
                self.assertTrue(media.get("examples"))
                for example in media["examples"].values():
                    self.assertEqual([e.message for e in validator.iter_errors(example["value"])], [])
