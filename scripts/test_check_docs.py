import tempfile
from pathlib import Path
import unittest

from check_docs import check_document


class DocumentationChecks(unittest.TestCase):
    def check(self, content):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            path = root / "README.md"
            path.write_text(content, encoding="utf-8")
            return check_document(path, root)

    def test_valid_document(self):
        self.assertEqual(self.check(
            '[self](README.md#unverified-anchor) [web](https://example.com)\n'
            '```json\n{"amount": 1000}\n```\n'), [])

    def test_missing_path(self):
        self.assertIn("missing link", self.check('[missing](absent.md)')[0])

    def test_outside_repo(self):
        self.assertIn("outside repo", self.check('[outside](../absent.md)')[0])

    def test_invalid_json(self):
        self.assertIn("invalid JSON", self.check('```json\n{"a":}\n```')[0])

    def test_unclosed_fence(self):
        self.assertIn("unclosed code fence", self.check('```text\nhello')[0])

    def test_non_json_numbers(self):
        for value in ("NaN", "Infinity", "-Infinity"):
            with self.subTest(value=value):
                self.assertIn("invalid JSON", self.check(f'```json\n{value}\n```')[0])

    def test_nested_shorter_fence_and_code_links(self):
        self.assertEqual(self.check('````text\n```\n[fake](absent.md)\n````'), [])

    def test_tilde_fence(self):
        self.assertEqual(self.check('~~~json\n{}\n~~~'), [])

    def test_local_paths_with_title_or_angle_brackets(self):
        self.assertEqual(self.check('[a](README.md "title") [b](<README.md>)'), [])


if __name__ == "__main__":
    unittest.main()
