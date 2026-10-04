"""Load CloudFormation YAML (short-form intrinsics) and resolve the few intrinsics tests need."""
from pathlib import Path
import re

import yaml

ROOT = Path(__file__).resolve().parents[2]


class _Loader(yaml.SafeLoader):
    pass


def _intrinsic(loader, suffix, node):
    name = "Ref" if suffix == "Ref" else "Fn::" + suffix
    if isinstance(node, yaml.ScalarNode):
        value = loader.construct_scalar(node)
        if suffix == "GetAtt":
            value = value.split(".", 1)
    elif isinstance(node, yaml.SequenceNode):
        value = loader.construct_sequence(node, deep=True)
    else:
        value = loader.construct_mapping(node, deep=True)
    return {name: value}


_Loader.add_multi_constructor("!", lambda loader, suffix, node: _intrinsic(loader, suffix, node))


def load(relative_path):
    return yaml.load((ROOT / relative_path).read_text(encoding="utf-8"), Loader=_Loader)


def resolve_sub(template, value):
    """Fn::Sub with parameter defaults and AWS pseudo parameters left as placeholders."""
    if isinstance(value, dict) and "Fn::Sub" in value:
        text = value["Fn::Sub"]
        defaults = {k: str(v.get("Default", "")) for k, v in template.get("Parameters", {}).items()}
        return re.sub(r"\$\{([^}]+)\}", lambda m: defaults.get(m.group(1), "<" + m.group(1) + ">"), text)
    return value
