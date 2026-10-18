"""TASK:WEB-01: storefront unit-test mocks must match the catalog response contract (spec section 5)."""
from pathlib import Path
import json
import unittest

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
BASE = "https://fashion.local/contracts/openapi/"
FIXTURES = ROOT / "web/storefront/src/test/fixtures"
SCHEMAS = {
    "products.json": "ProductPageResponse",
    "products-page2.json": "ProductPageResponse",
    "products-empty.json": "ProductPageResponse",
    "product.json": "PublicProductResponse",
    "collections.json": "PublicCollectionsResponse",
    "collection.json": "PublicCollectionResponse",
    "categories.json": "PublicCategoriesResponse",
    "brands.json": "PublicBrandsResponse",
}


class StorefrontFixtureContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        docs = {name: yaml.safe_load((ROOT / "contracts/openapi" / name).read_text(encoding="utf-8"))
                for name in ("catalog.yaml", "common.yaml")}
        cls.registry = Registry().with_resources(
            (BASE + name, Resource.from_contents(d, default_specification=DRAFT202012)) for name, d in docs.items())

    def test_every_fixture_is_mapped(self):
        self.assertEqual({p.name for p in FIXTURES.glob("*.json")}, set(SCHEMAS))

    def test_fixtures_match_response_schemas(self):
        for name, schema in SCHEMAS.items():
            with self.subTest(fixture=name):
                validator = Draft202012Validator({"$ref": BASE + "catalog.yaml#/components/schemas/" + schema},
                                                 registry=self.registry, format_checker=FormatChecker())
                data = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
                self.assertEqual([e.message for e in validator.iter_errors(data)], [])


if __name__ == "__main__":
    unittest.main()
