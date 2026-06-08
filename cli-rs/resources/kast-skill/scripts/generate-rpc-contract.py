#!/usr/bin/env python3
"""Generate human-readable RPC contract assets from commands.json."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path
from typing import Any

SCRIPT = Path(__file__).resolve()
SKILL_ROOT = SCRIPT.parents[1]
REFERENCES = SKILL_ROOT / "references"
DEFAULT_CATALOG = REFERENCES / "commands.json"
DEFAULT_YAML = REFERENCES / "commands.yaml"
DEFAULT_SAMPLES = REFERENCES / "requests"

PATH_SAMPLE = "/absolute/path/to/workspace/src/main/kotlin/example/Widget.kt"
WORKSPACE_SAMPLE = "/absolute/path/to/workspace"


def yaml_scalar(value: Any) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(value)
    return json.dumps(str(value), ensure_ascii=False)


def yaml_block(value: Any, indent: int = 0) -> list[str]:
    prefix = " " * indent
    if isinstance(value, dict):
        if not value:
            return [prefix + "{}"]
        lines: list[str] = []
        for key, child in value.items():
            rendered_key = yaml_scalar(key)
            if isinstance(child, (dict, list)) and child:
                lines.append(f"{prefix}{rendered_key}:")
                lines.extend(yaml_block(child, indent + 2))
            elif isinstance(child, (dict, list)):
                empty = "{}" if isinstance(child, dict) else "[]"
                lines.append(f"{prefix}{rendered_key}: {empty}")
            else:
                lines.append(f"{prefix}{rendered_key}: {yaml_scalar(child)}")
        return lines
    if isinstance(value, list):
        if not value:
            return [prefix + "[]"]
        lines = []
        for child in value:
            if isinstance(child, (dict, list)) and child:
                lines.append(prefix + "-")
                lines.extend(yaml_block(child, indent + 2))
            elif isinstance(child, (dict, list)):
                empty = "{}" if isinstance(child, dict) else "[]"
                lines.append(prefix + "- " + empty)
            else:
                lines.append(prefix + "- " + yaml_scalar(child))
        return lines
    return [prefix + yaml_scalar(value)]


def dump_yaml(value: Any) -> str:
    return "\n".join(yaml_block(value)) + "\n"


def request_required(request: dict[str, Any]) -> list[str]:
    explicit = request.get("required")
    if isinstance(explicit, list):
        return [str(name) for name in explicit]
    return [
        name
        for name, field in request.get("fields", {}).items()
        if isinstance(field, dict) and field.get("optional") is not True
    ]


def sample_integer(name: str) -> int:
    lower = name.lower()
    if "offset" in lower:
        return 128
    if lower == "endoffset":
        return 180
    if "line" in lower:
        return 42
    if "depth" in lower:
        return 2
    if "timeout" in lower:
        return 5000
    if "maxchildren" in lower:
        return 10
    if "maxtotal" in lower:
        return 50
    if "limit" in lower or "max" in lower:
        return 25
    return 1


def sample_string(name: str) -> str:
    lower = name.lower()
    if lower == "workspaceroot":
        return WORKSPACE_SAMPLE
    if lower in {"filepath", "targetfile", "contentfile", "filehint"}:
        return PATH_SAMPLE
    if lower == "fileglob":
        return "**/*.kt"
    if lower == "folderfilter":
        return "src/main/kotlin"
    if lower == "modulename":
        return ":analysis-api"
    if lower == "modulepath":
        return ":app"
    if lower == "sourcesset" or lower == "sourceset":
        return "main"
    if lower == "packageprefix":
        return "com.example"
    if lower in {"fqname", "fqnameprefix", "containingtype"}:
        return "com.example.Widget"
    if lower == "newname":
        return "RenamedWidget"
    if lower in {"symbol", "targetsymbol", "query", "pattern"}:
        return "Widget"
    if lower == "codesnippet":
        return "val widget = Widget()"
    if lower == "diagnosticcode":
        return "UNUSED_IMPORT"
    if lower == "content":
        return "fun added() = Unit\n"
    return f"example-{name}"


def sample_open_object(name: str) -> dict[str, Any]:
    lower = name.lower()
    if lower == "position":
        return {"filePath": PATH_SAMPLE, "offset": 128}
    if lower in {"edits", "item"}:
        return {
            "filePath": PATH_SAMPLE,
            "startOffset": 120,
            "endOffset": 180,
            "content": "val renamed = Widget()\n",
        }
    if lower == "filehashes":
        return {"filePath": PATH_SAMPLE, "sha256": "abc123"}
    if lower == "fileoperations":
        return {"type": "CREATE_FILE", "filePath": PATH_SAMPLE}
    return {"example": True}


def sample_field(name: str, field: dict[str, Any], maximal: bool) -> Any:
    enum_values = field.get("enum")
    if isinstance(enum_values, list) and enum_values:
        return enum_values[-1] if maximal else enum_values[0]

    field_type = field.get("type")
    if field_type == "string":
        return sample_string(name)
    if field_type == "integer":
        return sample_integer(name)
    if field_type == "boolean":
        return True
    if field_type == "array":
        items = field.get("items", "object")
        if isinstance(items, str):
            if items == "string":
                if name == "filePaths":
                    return [PATH_SAMPLE]
                return [sample_string(name[:-1] or "value")]
            if items == "integer":
                return [1]
            if items == "boolean":
                return [True]
            return [sample_open_object(name)]
        return [sample_field("item", items, maximal)]
    if field_type == "object":
        fields = field.get("fields")
        if isinstance(fields, dict):
            return sample_request({"fields": fields, "required": field.get("required")}, maximal)
        return sample_open_object(name)
    return sample_open_object(name)


def sample_request(request: dict[str, Any], maximal: bool) -> dict[str, Any]:
    fields = request.get("fields", {})
    required = set(request_required(request))
    selected = list(fields.keys()) if maximal else [name for name in fields.keys() if name in required]
    payload: dict[str, Any] = {}
    for name in selected:
        field = fields[name]
        payload[name] = sample_field(name, field, maximal)
    return payload


def request_path(samples_root: Path, category: str, method: str) -> Path:
    method_parts = method.split("/")
    if method_parts[0] == category:
        parts = [category, *method_parts[1:]]
    else:
        parts = [category, *method_parts]
    return samples_root.joinpath(*parts)


def request_payload(method: str, params: dict[str, Any]) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "method": method,
        "params": params,
        "id": 1,
    }


def generated_samples(catalog: dict[str, Any], samples_root: Path) -> dict[Path, str]:
    generated: dict[Path, str] = {}
    for method, command in catalog["commands"].items():
        base = request_path(samples_root, command["category"], method)
        variants = command.get("variants") or {}
        if variants:
            type_field = command["request"]["fields"]["type"]
            for variant_name, variant_request in variants.items():
                for label, maximal in (("minimal", False), ("maximal", True)):
                    params = {"type": variant_name}
                    params.update(sample_request(variant_request, maximal))
                    if "enum" in type_field and variant_name not in type_field["enum"]:
                        raise ValueError(f"{method} variant {variant_name} is missing from type enum")
                    path = base / variant_name / f"{label}.json"
                    generated[path] = json.dumps(request_payload(method, params), indent=2) + "\n"
        else:
            for label, maximal in (("minimal", False), ("maximal", True)):
                params = sample_request(command["request"], maximal)
                path = base / f"{label}.json"
                generated[path] = json.dumps(request_payload(method, params), indent=2) + "\n"
    return generated


def generated_files(catalog_path: Path, yaml_path: Path, samples_root: Path) -> dict[Path, str]:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    generated = {yaml_path: dump_yaml(catalog)}
    generated.update(generated_samples(catalog, samples_root))
    return generated


def check_files(files: dict[Path, str], samples_root: Path) -> list[str]:
    errors: list[str] = []
    expected_paths = set(files)
    for path, expected in files.items():
        if not path.exists():
            errors.append(f"missing generated file: {path}")
            continue
        actual = path.read_text(encoding="utf-8")
        if actual != expected:
            errors.append(f"outdated generated file: {path}")
    if samples_root.exists():
        for path in samples_root.rglob("*.json"):
            if path not in expected_paths:
                errors.append(f"unexpected generated sample: {path}")
    return errors


def write_files(files: dict[Path, str], samples_root: Path) -> None:
    if samples_root.exists():
        shutil.rmtree(samples_root)
    for path, content in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--yaml", type=Path, default=DEFAULT_YAML)
    parser.add_argument("--samples-root", type=Path, default=DEFAULT_SAMPLES)
    parser.add_argument("--check", action="store_true", help="fail if generated files are stale")
    args = parser.parse_args()

    files = generated_files(args.catalog, args.yaml, args.samples_root)
    if args.check:
        errors = check_files(files, args.samples_root)
        if errors:
            for error in errors:
                print(error, file=sys.stderr)
            return 1
        print(json.dumps({"ok": True, "checked": len(files)}, indent=2))
        return 0

    write_files(files, args.samples_root)
    print(json.dumps({"ok": True, "written": len(files)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
