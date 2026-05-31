#!/usr/bin/env python3
"""Validate Kast JSON-RPC request payloads against the packaged command catalog."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

SCRIPT = Path(__file__).resolve()
SKILL_ROOT = SCRIPT.parents[1]
REFERENCES = SKILL_ROOT / "references"
DEFAULT_CATALOG = REFERENCES / "commands.json"
DEFAULT_SAMPLES = REFERENCES / "requests"


def load_catalog(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    if path.suffix in {".yaml", ".yml"}:
        try:
            import yaml  # type: ignore[import-not-found]
        except ImportError as error:
            raise SystemExit(
                f"{path}: YAML catalog validation requires PyYAML; use commands.json instead"
            ) from error
        value = yaml.safe_load(text)
    else:
        value = json.loads(text)
    if not isinstance(value, dict):
        raise SystemExit(f"{path}: catalog root must be an object")
    return value


def load_request(raw: str | None, request_file: Path | None) -> Any:
    if request_file is not None:
        return json.loads(request_file.read_text(encoding="utf-8"))
    if raw is None:
        raise SystemExit("provide a request string, --request-file, or --all-samples")
    if raw.startswith("@"):
        return json.loads(Path(raw[1:]).read_text(encoding="utf-8"))
    candidate = Path(raw)
    if candidate.is_file():
        return json.loads(candidate.read_text(encoding="utf-8"))
    return json.loads(raw)


def error(path: str, message: str, expected: Any | None = None, actual: Any | None = None) -> dict[str, Any]:
    item: dict[str, Any] = {"path": path, "message": message}
    if expected is not None:
        item["expected"] = expected
    if actual is not None:
        item["actual"] = actual
    return item


def json_type(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


def request_required(request: dict[str, Any]) -> list[str]:
    explicit = request.get("required")
    if isinstance(explicit, list):
        return [str(name) for name in explicit]
    return [
        name
        for name, field in request.get("fields", {}).items()
        if isinstance(field, dict) and field.get("optional") is not True
    ]


def validate_field(path: str, value: Any, field: dict[str, Any]) -> list[dict[str, Any]]:
    errors: list[dict[str, Any]] = []
    if value is None:
        if field.get("nullable") is True:
            return errors
        return [error(path, "field is not nullable", actual=None)]

    enum_values = field.get("enum")
    if isinstance(enum_values, list) and value not in enum_values:
        errors.append(error(path, "value is outside the allowed enum", enum_values, value))
        return errors

    field_type = field.get("type")
    if field_type == "string":
        if not isinstance(value, str):
            errors.append(error(path, "expected string", "string", json_type(value)))
    elif field_type == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            errors.append(error(path, "expected integer", "integer", json_type(value)))
    elif field_type == "boolean":
        if not isinstance(value, bool):
            errors.append(error(path, "expected boolean", "boolean", json_type(value)))
    elif field_type == "array":
        if not isinstance(value, list):
            errors.append(error(path, "expected array", "array", json_type(value)))
        else:
            items = field.get("items", "object")
            for index, item in enumerate(value):
                item_path = f"{path}[{index}]"
                if isinstance(items, dict):
                    errors.extend(validate_field(item_path, item, items))
                else:
                    errors.extend(validate_field(item_path, item, {"type": items}))
    elif field_type == "object":
        if not isinstance(value, dict):
            errors.append(error(path, "expected object", "object", json_type(value)))
        elif isinstance(field.get("fields"), dict):
            errors.extend(validate_object(path, value, field))
    else:
        errors.append(error(path, "catalog field has unsupported type", actual=field_type))
    return errors


def validate_object(path: str, value: dict[str, Any], request: dict[str, Any]) -> list[dict[str, Any]]:
    fields = request.get("fields", {})
    errors: list[dict[str, Any]] = []
    if not isinstance(fields, dict):
        return [error(path, "catalog request fields must be an object")]

    for name in request_required(request):
        if name not in value:
            errors.append(error(f"{path}.{name}", "missing required field"))

    for name in value:
        if name not in fields:
            errors.append(error(f"{path}.{name}", "unknown field"))
            continue
        field = fields[name]
        if not isinstance(field, dict):
            errors.append(error(f"{path}.{name}", "catalog field must be an object"))
            continue
        errors.extend(validate_field(f"{path}.{name}", value[name], field))
    return errors


def variant_request(command: dict[str, Any], variant_name: str) -> dict[str, Any] | None:
    variants = command.get("variants")
    if not isinstance(variants, dict):
        return None
    variant = variants.get(variant_name)
    if not isinstance(variant, dict):
        return None
    type_field = dict(command["request"]["fields"]["type"])
    type_field["enum"] = [variant_name]
    fields = {"type": type_field}
    fields.update(variant.get("fields", {}))
    return {"fields": fields, "required": ["type", *request_required(variant)]}


def validate_params(params: Any, command: dict[str, Any]) -> list[dict[str, Any]]:
    if not isinstance(params, dict):
        return [error("params", "expected params object", "object", json_type(params))]

    variants = command.get("variants")
    if isinstance(variants, dict) and variants:
        variant_name = params.get("type")
        if not isinstance(variant_name, str):
            return [error("params.type", "variant request requires string type discriminator")]
        request = variant_request(command, variant_name)
        if request is None:
            return [error("params.type", "unknown request variant", sorted(variants), variant_name)]
        return validate_object("params", params, request)
    return validate_object("params", params, command["request"])


def validate_request(request: Any, catalog: dict[str, Any]) -> tuple[bool, list[dict[str, Any]], str | None]:
    errors: list[dict[str, Any]] = []
    if not isinstance(request, dict):
        return False, [error("$", "request must be a JSON object", "object", json_type(request))], None

    allowed_top_level = {"jsonrpc", "method", "params", "id"}
    for name in request:
        if name not in allowed_top_level:
            errors.append(error(name, "unknown top-level field"))

    if request.get("jsonrpc") != "2.0":
        errors.append(error("jsonrpc", "JSON-RPC version must be 2.0", "2.0", request.get("jsonrpc")))

    method = request.get("method")
    if not isinstance(method, str):
        errors.append(error("method", "method must be a string", "string", json_type(method)))
        return False, errors, None

    commands = catalog.get("commands")
    if not isinstance(commands, dict):
        errors.append(error("catalog.commands", "catalog commands must be an object"))
        return False, errors, method

    command = commands.get(method)
    if not isinstance(command, dict):
        errors.append(error("method", "unknown Kast RPC method", sorted(commands), method))
        return False, errors, method

    if "id" in request and isinstance(request["id"], bool):
        errors.append(error("id", "id must not be boolean", ["string", "integer", "null"], "boolean"))

    params = request.get("params", {})
    errors.extend(validate_params(params, command))
    return not errors, errors, method


def validate_one(request: Any, catalog: dict[str, Any], source: str | None = None) -> dict[str, Any]:
    ok, errors, method = validate_request(request, catalog)
    result: dict[str, Any] = {"ok": ok}
    if source is not None:
        result["source"] = source
    if method is not None:
        result["method"] = method
    if errors:
        result["errors"] = errors
    return result


def validate_samples(samples_root: Path, catalog: dict[str, Any]) -> dict[str, Any]:
    failures: list[dict[str, Any]] = []
    count = 0
    for path in sorted(samples_root.rglob("*.json")):
        count += 1
        request = json.loads(path.read_text(encoding="utf-8"))
        result = validate_one(request, catalog, str(path))
        if not result["ok"]:
            failures.append(result)
    return {
        "ok": not failures,
        "validated": count,
        "errors": failures,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("request", nargs="?", help="JSON request string or request file path")
    parser.add_argument("--request-file", type=Path)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--all-samples", action="store_true")
    parser.add_argument("--samples-root", type=Path, default=DEFAULT_SAMPLES)
    args = parser.parse_args()

    catalog = load_catalog(args.catalog)
    if args.all_samples:
        result = validate_samples(args.samples_root, catalog)
    else:
        request = load_request(args.request, args.request_file)
        result = validate_one(request, catalog)

    print(json.dumps(result, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
