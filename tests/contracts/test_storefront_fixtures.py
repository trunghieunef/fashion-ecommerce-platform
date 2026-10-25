"""TASK:WEB-01, TASK:WEB-02a/b: storefront unit-test mocks must match the response contracts (catalog, user, cart)."""
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
CATALOG = "catalog.yaml#/components/schemas/"
USER = "user.yaml#/components/schemas/"
CART = "cart.yaml#/components/schemas/"
ORDER = "order.yaml#/components/schemas/"
# Value: "<contract file>#<JSON pointer>"; inline response schemas (no component name) are addressed by their path.
SCHEMAS = {
    "products.json": CATALOG + "ProductPageResponse",
    "products-page2.json": CATALOG + "ProductPageResponse",
    "products-empty.json": CATALOG + "ProductPageResponse",
    "product.json": CATALOG + "PublicProductResponse",
    "collections.json": CATALOG + "PublicCollectionsResponse",
    "collection.json": CATALOG + "PublicCollectionResponse",
    "categories.json": CATALOG + "PublicCategoriesResponse",
    "brands.json": CATALOG + "PublicBrandsResponse",
    "session.json": USER + "SessionResponse",
    "refresh.json": USER + "RefreshResponse",
    "profile.json": USER + "ProfileResponse",
    "address.json": USER + "AddressResponse",
    "forgot.json": USER + "ForgotAcceptedResponse",
    "cart-guest.json": CART + "CartResponse",
    "cart-member.json": CART + "CartResponse",
    "cart-empty.json": CART + "CartResponse",
    "merge.json": CART + "MergeResponse",
    "checkout-token.json": CART + "CheckoutTokenResponse",
    "quote.json": ORDER + "QuoteResponse",
    "order-processing.json": ORDER + "OrderResponse",
    "order-confirmed.json": ORDER + "OrderResponse",
    "order-cancelled.json": ORDER + "OrderResponse",
    "orders-mine.json": ORDER + "OrderPageResponse",
    "addresses.json": "user.yaml#/paths/~1api~1v1~1users~1me~1addresses/get/responses/200/content/application~1json/schema",
}


class StorefrontFixtureContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        docs = {name: yaml.safe_load((ROOT / "contracts/openapi" / name).read_text(encoding="utf-8"))
                for name in ("catalog.yaml", "user.yaml", "cart.yaml", "order.yaml", "common.yaml")}
        cls.registry = Registry().with_resources(
            (BASE + name, Resource.from_contents(d, default_specification=DRAFT202012)) for name, d in docs.items())

    def test_every_fixture_is_mapped(self):
        self.assertEqual({p.name for p in FIXTURES.glob("*.json")}, set(SCHEMAS))

    def test_fixtures_match_response_schemas(self):
        for name, schema in SCHEMAS.items():
            with self.subTest(fixture=name):
                validator = Draft202012Validator({"$ref": BASE + schema},
                                                 registry=self.registry, format_checker=FormatChecker())
                data = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
                self.assertEqual([e.message for e in validator.iter_errors(data)], [])


if __name__ == "__main__":
    unittest.main()
