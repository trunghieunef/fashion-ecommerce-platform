"""Check repo docs without dependencies or network access.

Scope: root README + docs/**/*.md; fenced blocks, JSON syntax and simple
inline link paths. Not a CommonMark parser: anchors, reference links, external
URLs and Mermaid rendering need separate review/tooling (PLT-02).
"""

import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
LINK = re.compile(r"\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^\s)]+)(?:\s+\"[^\"\n]*\")?\s*\)")


def reject_non_json_constant(value):
    raise ValueError(f"{value} is not a JSON number")


def check_document(path, root):
    errors = []
    fence = None
    body = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = FENCE.match(line)
        if fence:
            marker, language, start = fence
            if (match and match[1][0] == marker[0]
                    and len(match[1]) >= len(marker) and not match[2].strip()):
                if language == "json":
                    try:
                        json.loads("\n".join(body), parse_constant=reject_non_json_constant)
                    except ValueError as exc:
                        errors.append(f"{path.relative_to(root)}:{start}: invalid JSON: {exc}")
                fence = None
            else:
                body.append(line)
            continue
        if match:
            fence = (match[1], match[2].strip().lower(), number)
            body = []
            continue
        for link in LINK.finditer(line):
            target = link[1].strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            local_path = unquote(parsed.path)
            destination = (root / local_path.lstrip("/") if local_path.startswith("/")
                           else path.parent / local_path).resolve()
            if not destination.is_relative_to(root):
                errors.append(f"{path.relative_to(root)}:{number}: link outside repo: {target}")
            elif not destination.exists():
                errors.append(f"{path.relative_to(root)}:{number}: missing link: {target}")
    if fence:
        errors.append(f"{path.relative_to(root)}:{fence[2]}: unclosed code fence")
    return errors


def main():
    root = Path(__file__).resolve().parents[1]
    paths = [root / "README.md", *sorted((root / "docs").rglob("*.md"))]
    errors = [error for path in paths for error in check_document(path, root)]
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"PASS: {len(paths)} Markdown files; fences, JSON and inline local link paths.")
    print("Not checked: anchors, reference links, external URLs, Mermaid, application or deployment.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
