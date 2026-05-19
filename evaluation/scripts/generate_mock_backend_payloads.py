#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

TOOL_METHODS = {
    "kast_workspace_files": "raw/workspace-files",
    "kast_workspace_symbol": "raw/workspace-symbol",
    "kast_workspace_search": "raw/workspace-search",
    "kast_file_outline": "raw/file-outline",
    "kast_scaffold": "symbol/scaffold",
    "kast_resolve": "symbol/resolve",
    "kast_references": "symbol/references",
    "kast_callers": "symbol/callers",
    "kast_metrics": "database/metrics",
    "kast_diagnostics": "raw/diagnostics",
    "kast_rename": "symbol/rename",
    "kast_write_and_validate": "symbol/write-and-validate",
}

PATH_KEYS = {
    "file",
    "filePath",
    "fileHint",
    "targetFile",
    "contentFile",
    "workspaceRoot",
    "logFile",
}
PATH_LIST_KEYS = {
    "files",
    "filePaths",
    "affectedFiles",
    "createdFiles",
    "deletedFiles",
    "expectedFiles",
    "decoyFiles",
    "sourceRoots",
    "refreshedFiles",
    "removedFiles",
}
MATCHER_KEYS = {
    "symbol",
    "kind",
    "containingType",
    "fileHint",
    "targetFile",
    "filePath",
    "moduleName",
    "metric",
    "pattern",
}
INFRA_FAILURE_STAGE = "extension" + ".resolve"
BINARY_NOT_RESOLVED_FRAGMENT = "binary not " + "resolved"
STALE_BENCHMARK_WORKTREE = re.compile(r"(^|/)\.benchmarks/.*/worktree(/|$)")


def load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text())
    if not isinstance(payload, dict):
        raise ValueError(f"{path} must contain a JSON object.")
    return payload


def is_path_like(value: str) -> bool:
    return "/" in value and "://" not in value


def workspace_relative(value: str, workspace_root: Path | None) -> str:
    if not value:
        return value
    if value in {".", "$WORKSPACE"}:
        return "."
    path = Path(value)
    if path.is_absolute() and workspace_root is not None:
        try:
            return path.relative_to(workspace_root).as_posix()
        except ValueError:
            return value
    return value


def canonicalize_paths(value: Any, *, key: str | None = None, workspace_root: Path | None = None) -> Any:
    if isinstance(value, list):
        return [
            workspace_relative(item, workspace_root)
            if isinstance(item, str) and (key in PATH_LIST_KEYS or is_path_like(item))
            else canonicalize_paths(item, key=key, workspace_root=workspace_root)
            for item in value
        ]
    if isinstance(value, dict):
        return {
            item_key: canonicalize_paths(item_value, key=item_key, workspace_root=workspace_root)
            for item_key, item_value in value.items()
        }
    if isinstance(value, str) and (key in PATH_KEYS or is_path_like(value)):
        return workspace_relative(value, workspace_root)
    return value


def parse_json_maybe(value: Any) -> Any:
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return None
    return value


def extract_rpc_result(raw_result: Any) -> dict[str, Any] | None:
    candidate = parse_json_maybe(raw_result)
    if isinstance(candidate, dict) and "content" in candidate:
        nested = parse_json_maybe(candidate.get("content"))
        if nested is not None:
            candidate = nested
    if isinstance(candidate, dict) and "detailedContent" in candidate and "result" not in candidate:
        nested = parse_json_maybe(candidate.get("detailedContent"))
        if nested is not None:
            candidate = nested
    if (
        isinstance(candidate, dict)
        and "result" not in candidate
        and ("content" in candidate or "detailedContent" in candidate)
        and not {"ok", "type"}.intersection(candidate)
    ):
        return None
    if not isinstance(candidate, dict):
        return None
    if "error" in candidate:
        return None
    if isinstance(candidate.get("result"), dict):
        candidate = candidate["result"]
        if "content" in candidate or "detailedContent" in candidate:
            nested = parse_json_maybe(candidate.get("content"))
            if nested is None:
                nested = parse_json_maybe(candidate.get("detailedContent"))
            if nested is None:
                return None
            return extract_rpc_result(nested)
    return candidate if isinstance(candidate, dict) else None


def is_infrastructure_failure_result(result: dict[str, Any]) -> bool:
    if result.get("ok") is not False:
        return False
    stage = str(result.get("stage") or "").strip()
    message = str(result.get("message") or "").lower()
    return (
        stage == INFRA_FAILURE_STAGE
        or BINARY_NOT_RESOLVED_FRAGMENT in message
        or "no resolved kast cli" in message
    )


def matcher_from_args(args: dict[str, Any], workspace_root: Path | None) -> dict[str, Any]:
    matcher = {
        key: canonicalize_paths(value, key=key, workspace_root=workspace_root)
        for key, value in args.items()
        if key in MATCHER_KEYS and value not in (None, "")
    }
    return matcher or {"type": "any"}


def contains_foreign_workspace_fragment(value: str, workspace_root: Path | None) -> bool:
    if workspace_root is None:
        return False
    normalized = value.replace("\\", "/")
    root = workspace_root.as_posix().rstrip("/")
    parent = workspace_root.parent.as_posix().rstrip("/")
    if not parent or parent == ".":
        return False
    for match in re.finditer(re.escape(parent) + r"/[^\s\"'{}\[\],)]+", normalized):
        candidate = match.group(0).rstrip(".,;:")
        if candidate != root and not candidate.startswith(root + "/"):
            return True
    return False


def is_contaminated_history_value(value: Any, *, key: str | None = None, workspace_root: Path | None = None) -> bool:
    if isinstance(value, list):
        return any(is_contaminated_history_value(item, key=key, workspace_root=workspace_root) for item in value)
    if isinstance(value, dict):
        return any(
            is_contaminated_history_value(item_value, key=item_key, workspace_root=workspace_root)
            for item_key, item_value in value.items()
        )
    if not isinstance(value, str) or not value:
        return False
    normalized = value.replace("\\", "/")
    if STALE_BENCHMARK_WORKTREE.search(normalized):
        return True
    if contains_foreign_workspace_fragment(value, workspace_root):
        return True
    if key not in PATH_KEYS and key not in PATH_LIST_KEYS and not is_path_like(value):
        return False
    path = Path(value)
    if not path.is_absolute() or value == "/dev/null":
        return False
    if workspace_root is None:
        return True
    try:
        path.relative_to(workspace_root)
        return False
    except ValueError:
        return True


def entry_key(entry: dict[str, Any]) -> str:
    return json.dumps(
        {"method": entry.get("method"), "matcher": entry.get("matcher", {})},
        sort_keys=True,
    )


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict):
            rows.append(row)
    return rows


def event_log_paths(root: Path) -> list[Path]:
    if root.is_file():
        return [root] if root.name in {"sdk-events.jsonl", "events.jsonl"} else []
    paths: list[Path] = []
    for filename in ("sdk-events.jsonl", "events.jsonl"):
        paths.extend(root.rglob(filename))
    return sorted(set(paths))


def history_entries(history_roots: list[Path], *, workspace_root: Path | None) -> tuple[list[dict[str, Any]], int]:
    entries: list[dict[str, Any]] = []
    rejected = 0
    seen: set[str] = set()
    for root in history_roots:
        if not root.exists():
            continue
        for events_path in event_log_paths(root):
            starts: dict[str, dict[str, Any]] = {}
            for event in read_jsonl(events_path):
                data = event.get("data") if isinstance(event.get("data"), dict) else {}
                tool_call_id = str(data.get("toolCallId") or "")
                if event.get("type") == "tool.execution_start":
                    starts[tool_call_id] = data
                    continue
                if event.get("type") != "tool.execution_complete":
                    continue
                tool_name = str(data.get("toolName") or starts.get(tool_call_id, {}).get("toolName") or "")
                method = TOOL_METHODS.get(tool_name)
                if method is None:
                    continue
                if data.get("success") is not True:
                    rejected += 1
                    continue
                result = extract_rpc_result(data.get("result"))
                if result is None:
                    rejected += 1
                    continue
                if is_infrastructure_failure_result(result):
                    rejected += 1
                    continue
                args = starts.get(tool_call_id, {}).get("arguments")
                matcher = matcher_from_args(args if isinstance(args, dict) else {}, workspace_root)
                canonical_result = canonicalize_paths(result, workspace_root=workspace_root)
                if is_contaminated_history_value(matcher, workspace_root=workspace_root) or is_contaminated_history_value(
                    canonical_result,
                    workspace_root=workspace_root,
                ):
                    rejected += 1
                    continue
                entry = {
                    "method": method,
                    "matcher": matcher,
                    "result": canonical_result,
                    "provenance": {
                        "source": "history",
                        "source_file": str(events_path),
                        "tool_call_id": tool_call_id,
                        "fallback": False,
                    },
                }
                key = entry_key(entry)
                if key in seen:
                    continue
                seen.add(key)
                entries.append(entry)
    return entries, rejected


def slot_file(slot: dict[str, Any] | None) -> str:
    return str((slot or {}).get("file") or "src/main/kotlin/Mock.kt")


def location(file_path: str, token: str, *, line: int = 1, column: int = 1) -> dict[str, Any]:
    return {
        "filePath": file_path,
        "startOffset": 0,
        "endOffset": len(token),
        "startLine": line,
        "startColumn": column,
        "preview": token,
    }


def search_match(file_path: str, token: str, *, line: int = 1, column: int = 1) -> dict[str, Any]:
    return {
        "filePath": file_path,
        "lineNumber": line,
        "columnNumber": column,
        "preview": token,
    }


def search_scope(candidate_file_count: int) -> dict[str, Any]:
    count = max(1, candidate_file_count)
    return {
        "visibility": "PUBLIC",
        "scope": "DEPENDENT_MODULES",
        "exhaustive": True,
        "candidateFileCount": count,
        "searchedFileCount": count,
    }


def reference_locations(files: list[Any], token: str, minimum_count: int | None = None) -> list[dict[str, Any]]:
    file_paths = [str(file_path) for file_path in files if str(file_path).strip()]
    if not file_paths:
        file_paths = ["src/main/kotlin/Mock.kt"]
    target_count = max(len(file_paths), int(minimum_count or 0), 1)
    return [
        location(
            file_paths[index % len(file_paths)],
            token,
            line=1 + (index // len(file_paths)),
        )
        for index in range(target_count)
    ]


def caller_file_from_fq_name(caller_name: str, default_file: str) -> str:
    parts = caller_name.split(".")
    class_index = next(
        (
            index
            for index in range(len(parts) - 1, -1, -1)
            if parts[index] and parts[index][0].isupper()
        ),
        None,
    )
    if class_index is None:
        return default_file
    default_parts = Path(default_file).parts
    module_root = default_parts[0] if default_parts else "src"
    class_name = parts[class_index]
    package_name = ".".join(parts[:class_index])
    source_set = "test" if class_name.endswith("Test") else "main"
    package_path = package_name.replace(".", "/")
    return f"{module_root}/src/{source_set}/kotlin/{package_path}/{class_name}.kt"


def caller_reference_locations(caller_names: list[Any], default_file: str) -> list[dict[str, Any]]:
    return [
        location(caller_file_from_fq_name(str(caller_name), default_file), str(caller_name).rsplit(".", 1)[-1])
        for caller_name in caller_names
        if str(caller_name).strip()
    ]


def symbol_from_slot(slot: dict[str, Any], *, kind: str = "CLASS") -> dict[str, Any]:
    symbol = str(slot.get("symbol") or str(slot.get("fqName") or "Mock").split(".")[-1])
    return {
        "fqName": str(slot.get("fqName") or symbol),
        "kind": kind,
        "location": location(slot_file(slot), symbol),
        "containingDeclaration": str(slot.get("containingType") or "").rsplit(".", 1)[0] or None,
    }


def symbol_from_containing_type(slot: dict[str, Any], *, kind: str = "CLASS") -> dict[str, Any] | None:
    containing_type = str(slot.get("containingType") or "").strip()
    if not containing_type:
        return None
    name = containing_type.rsplit(".", 1)[-1]
    return {
        "fqName": containing_type,
        "kind": kind,
        "location": location(slot_file(slot), name),
        "containingDeclaration": containing_type.rsplit(".", 1)[0] if "." in containing_type else None,
    }


def symbol_name_variants(*values: Any) -> list[str]:
    names: list[str] = []
    for value in values:
        if not isinstance(value, str):
            continue
        text = value.strip()
        if not text:
            continue
        names.append(text)
        if "." in text:
            names.append(text.rsplit(".", 1)[-1])
    return list(dict.fromkeys(names))


def caller_symbol_variants(value: Any) -> list[str]:
    variants = symbol_name_variants(value)
    if isinstance(value, str):
        short_name = value.rsplit(".", 1)[-1]
        if " " in short_name:
            variants.append(f"`{short_name}`")
            if "." in value:
                variants.append(f"{value.rsplit('.', 1)[0]}.`{short_name}`")
        variants.extend(
            token
            for token in re.findall(r"[A-Za-z][A-Za-z0-9_-]{3,}", short_name)
            if token not in {"with", "from", "into", "that", "this"}
        )
    return list(dict.fromkeys(variants))


def slot_symbol_variants(slot: dict[str, Any]) -> list[str]:
    return symbol_name_variants(slot.get("symbol"), slot.get("fqName"))


def symbol_matcher_variants(
    symbol_name: str,
    *,
    kind: str | None = None,
    file_hint: str | None = None,
    containing_type: str | None = None,
) -> list[dict[str, Any]]:
    qualifiers = [
        (key, str(value).strip())
        for key, value in (
            ("kind", kind),
            ("containingType", containing_type),
            ("fileHint", file_hint),
        )
        if value not in (None, "")
    ]
    variants: list[dict[str, Any]] = []
    seen: set[str] = set()
    for mask in sorted(range(1 << len(qualifiers)), key=lambda value: (-value.bit_count(), value)):
        matcher: dict[str, Any] = {"symbol": symbol_name}
        for index, (key, value) in enumerate(qualifiers):
            if mask & (1 << index):
                matcher[key] = value
        matcher_key = json.dumps(matcher, sort_keys=True)
        if matcher_key in seen:
            continue
        seen.add(matcher_key)
        variants.append(matcher)
    return variants


def collect_known_files(slots: dict[str, Any]) -> list[str]:
    files: set[str] = set()
    for slot in slots.values():
        if not isinstance(slot, dict):
            continue
        if isinstance(slot.get("file"), str):
            files.add(slot["file"])
        expected = slot.get("expected")
        if isinstance(expected, dict):
            for key in ("expectedFiles", "decoyFiles", "expectedConsumerFiles", "affectedFiles"):
                values = expected.get(key)
                if isinstance(values, list):
                    files.update(str(item) for item in values if isinstance(item, str))
            implementations = expected.get("implementations")
            if isinstance(implementations, list):
                for item in implementations:
                    if isinstance(item, dict) and isinstance(item.get("file"), str):
                        files.add(item["file"])
    return sorted(files)


def fallback_workspace_files(bindings: dict[str, Any]) -> dict[str, Any]:
    slots = bindings.get("slots") if isinstance(bindings.get("slots"), dict) else {}
    module_slot = slots.get("MODULE_LIST") if isinstance(slots.get("MODULE_LIST"), dict) else {}
    modules = module_slot.get("modules") if isinstance(module_slot.get("modules"), list) else ["mock.main"]
    counts = (
        module_slot.get("expected", {}).get("moduleFileCounts", {})
        if isinstance(module_slot.get("expected"), dict)
        else {}
    )
    known_files = collect_known_files(slots)
    return {
        "type": "WORKSPACE_FILES_SUCCESS",
        "ok": True,
        "query": {"workspaceRoot": ".", "includeFiles": True, "moduleName": None, "maxFilesPerModule": None},
        "modules": [
            {
                "name": str(module),
                "sourceRoots": [],
                "dependencyModuleNames": [],
                "files": known_files,
                "filesTruncated": False,
                "fileCount": int(counts.get(str(module), len(known_files)) or 0),
            }
            for module in modules
        ],
        "schemaVersion": 1,
        "logFile": ".kast/mock-backend.log",
    }


def fallback_symbols(bindings: dict[str, Any]) -> list[dict[str, Any]]:
    slots = bindings.get("slots") if isinstance(bindings.get("slots"), dict) else {}
    symbols: list[dict[str, Any]] = []
    kind_by_slot = {
        "SEALED_HIERARCHY": "INTERFACE",
        "DISAMBIGUATE_MEMBER": "PROPERTY",
        "CROSS_MODULE_CLASS": "INTERFACE",
        "OVERLOADED_OR_COMMON_FUNCTION": "FUNCTION",
        "RENAME_TARGET": "CLASS",
        "LARGE_CLASS": "CLASS",
    }
    for slot_name, kind in kind_by_slot.items():
        slot = slots.get(slot_name)
        if isinstance(slot, dict):
            symbols.append(symbol_from_slot(slot, kind=kind))
            containing_type_symbol = symbol_from_containing_type(slot)
            if containing_type_symbol is not None:
                symbols.append(containing_type_symbol)
            implementations = slot.get("expected", {}).get("implementations") if isinstance(slot.get("expected"), dict) else None
            if isinstance(implementations, list):
                for implementation in implementations:
                    if isinstance(implementation, dict):
                        symbols.append(symbol_from_slot(implementation, kind="CLASS"))
    return symbols


def fallback_entries(bindings: dict[str, Any]) -> list[dict[str, Any]]:
    slots = bindings.get("slots") if isinstance(bindings.get("slots"), dict) else {}
    symbols = fallback_symbols(bindings)
    first_symbol = symbols[0] if symbols else {
        "fqName": "mock.Symbol",
        "kind": "CLASS",
        "location": location("src/main/kotlin/Mock.kt", "Symbol"),
    }
    cross_module = slots.get("CROSS_MODULE_CLASS") if isinstance(slots.get("CROSS_MODULE_CLASS"), dict) else {}
    member = slots.get("DISAMBIGUATE_MEMBER") if isinstance(slots.get("DISAMBIGUATE_MEMBER"), dict) else {}
    function = slots.get("OVERLOADED_OR_COMMON_FUNCTION") if isinstance(slots.get("OVERLOADED_OR_COMMON_FUNCTION"), dict) else {}
    large_class = slots.get("LARGE_CLASS") if isinstance(slots.get("LARGE_CLASS"), dict) else {}
    rename_target = slots.get("RENAME_TARGET") if isinstance(slots.get("RENAME_TARGET"), dict) else {}
    reference_files = (
        cross_module.get("expected", {}).get("expectedConsumerFiles")
        if isinstance(cross_module.get("expected"), dict)
        else None
    ) or (
        member.get("expected", {}).get("expectedFiles")
        if isinstance(member.get("expected"), dict)
        else None
    ) or [first_symbol["location"]["filePath"]]
    references = [location(str(file_path), first_symbol["fqName"].split(".")[-1]) for file_path in reference_files]
    search_matches = [search_match(str(file_path), first_symbol["fqName"].split(".")[-1]) for file_path in reference_files]
    scope = search_scope(len(reference_files))
    caller_names = (
        function.get("expected", {}).get("expectedCallerFqNames")
        if isinstance(function.get("expected"), dict)
        else None
    ) or []
    caller_nodes = [
        {
            "symbol": {
                "fqName": str(name),
                "kind": "FUNCTION",
                "location": location(caller_file_from_fq_name(str(name), slot_file(function)), str(name).split(".")[-1]),
            },
            "callSite": location(caller_file_from_fq_name(str(name), slot_file(function)), str(name).split(".")[-1]),
            "children": [],
        }
        for name in caller_names
    ]

    def resolve_entry(symbol_name: str, symbol: dict[str, Any]) -> dict[str, Any]:
        return {
            "method": "symbol/resolve",
            "matcher": {"symbol": symbol_name},
            "result": {
                "type": "RESOLVE_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "symbol": symbol_name, "fileHint": None, "kind": None, "containingType": None},
                "symbol": symbol,
                "filePath": symbol["location"]["filePath"],
                "offset": symbol["location"]["startOffset"],
                "candidate": {"line": symbol["location"]["startLine"], "column": symbol["location"]["startColumn"], "context": symbol["location"]["preview"]},
                "candidateCount": 1,
                "alternatives": [],
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        }

    resolve_entries = []
    for symbol in symbols:
        for symbol_name in symbol_name_variants(symbol.get("fqName")):
            resolve_entries.append(resolve_entry(symbol_name, symbol))
    if not resolve_entries:
        resolve_entries.append({**resolve_entry(first_symbol["fqName"].split(".")[-1], first_symbol), "matcher": {"type": "any"}})
    for caller_name in caller_names:
        caller_symbol = {
            "fqName": str(caller_name),
            "kind": "FUNCTION",
            "location": location(slot_file(function), str(caller_name).rsplit(".", 1)[-1]),
            "containingDeclaration": str(caller_name).rsplit(".", 1)[0] if "." in str(caller_name) else None,
        }
        for symbol_name in caller_symbol_variants(caller_name):
            resolve_entries.append(resolve_entry(symbol_name, caller_symbol))
    reference_entries = []
    reference_slots = [
        (member, "PROPERTY"),
        (cross_module, "INTERFACE"),
        (function, "FUNCTION"),
        (rename_target, "CLASS"),
    ]

    def append_reference_entry(
        *,
        matcher: dict[str, Any],
        target_symbol: dict[str, Any],
        reference_locations: list[dict[str, Any]],
        reference_scope: dict[str, Any],
        containing_type: str | None = None,
    ) -> None:
        symbol_name = str(matcher["symbol"])
        reference_entries.append(
            {
                "method": "symbol/references",
                "matcher": matcher,
                "result": {
                    "type": "REFERENCES_SUCCESS",
                    "ok": True,
                    "query": {
                        "workspaceRoot": ".",
                        "symbol": symbol_name,
                        "fileHint": matcher.get("fileHint"),
                        "kind": matcher.get("kind"),
                        "containingType": matcher.get("containingType", containing_type),
                        "includeDeclaration": True,
                    },
                    "symbol": target_symbol,
                    "filePath": target_symbol["location"]["filePath"],
                    "offset": target_symbol["location"]["startOffset"],
                    "references": reference_locations,
                    "searchScope": reference_scope,
                    "declaration": target_symbol,
                    "candidateCount": 1,
                    "alternatives": [],
                    "logFile": ".kast/mock-backend.log",
                },
                "provenance": {"source": "bindings", "fallback": True},
            }
        )

    for slot, slot_kind in reference_slots:
        if not isinstance(slot, dict) or not slot.get("symbol"):
            continue
        slot_symbol = symbol_from_slot(slot, kind=slot_kind)
        containing_type_symbol = symbol_from_containing_type(slot)
        expected = slot.get("expected") if isinstance(slot.get("expected"), dict) else {}
        files = (
            expected.get("expectedFiles")
            or expected.get("expectedConsumerFiles")
            or expected.get("affectedFiles")
            or [slot_file(slot)]
        )
        minimum_references = expected.get("minimumReferences") if isinstance(expected.get("minimumReferences"), int) else None
        slot_refs = (
            caller_reference_locations(caller_names, slot_file(function))
            if slot is function and caller_names
            else reference_locations(files, str(slot.get("symbol")), minimum_references)
        )
        slot_scope = search_scope(len(slot_refs))
        reference_targets = [(slot_symbol, slot_symbol_variants(slot))]
        if containing_type_symbol is not None:
            reference_targets.append((containing_type_symbol, symbol_name_variants(containing_type_symbol["fqName"])))
        for target_symbol, target_names in reference_targets:
            for symbol_name in target_names:
                containing_type = str(slot.get("containingType") or "").strip() or None
                if target_symbol is not slot_symbol:
                    containing_type = None
                for matcher in symbol_matcher_variants(
                    symbol_name,
                    kind=str(target_symbol.get("kind") or slot_kind),
                    file_hint=target_symbol["location"]["filePath"],
                    containing_type=containing_type,
                ):
                    append_reference_entry(
                        matcher=matcher,
                        target_symbol=target_symbol,
                        reference_locations=slot_refs,
                        reference_scope=slot_scope,
                        containing_type=containing_type,
                    )
    for caller_name in caller_names:
        caller_symbol = {
            "fqName": str(caller_name),
            "kind": "FUNCTION",
            "location": location(
                caller_file_from_fq_name(str(caller_name), slot_file(function)),
                str(caller_name).rsplit(".", 1)[-1],
            ),
            "containingDeclaration": str(caller_name).rsplit(".", 1)[0] if "." in str(caller_name) else None,
        }
        caller_refs = [
            location(
                caller_file_from_fq_name(str(caller_name), slot_file(function)),
                str(caller_name).rsplit(".", 1)[-1],
            )
        ]
        caller_scope = search_scope(len(caller_refs))
        for symbol_name in caller_symbol_variants(caller_name):
            for matcher in symbol_matcher_variants(
                symbol_name,
                kind="FUNCTION",
                file_hint=slot_file(function),
                containing_type=caller_symbol["containingDeclaration"],
            ):
                append_reference_entry(
                    matcher=matcher,
                    target_symbol=caller_symbol,
                    reference_locations=caller_refs,
                    reference_scope=caller_scope,
                    containing_type=caller_symbol["containingDeclaration"],
                )
    if not reference_entries:
        reference_entries.append(
            {
                "method": "symbol/references",
                "matcher": {"type": "any"},
                "result": {
                    "type": "REFERENCES_SUCCESS",
                    "ok": True,
                    "query": {"workspaceRoot": ".", "symbol": first_symbol["fqName"].split(".")[-1], "fileHint": None, "kind": None, "containingType": None, "includeDeclaration": True},
                    "symbol": first_symbol,
                    "filePath": first_symbol["location"]["filePath"],
                    "offset": first_symbol["location"]["startOffset"],
                    "references": references,
                    "searchScope": scope,
                    "declaration": first_symbol,
                    "candidateCount": 1,
                    "alternatives": [],
                    "logFile": ".kast/mock-backend.log",
                },
                "provenance": {"source": "bindings", "fallback": True},
            }
        )
    caller_entries = []
    if isinstance(function, dict) and function.get("symbol"):
        function_symbol = symbol_from_slot(function, kind="FUNCTION")

        def append_caller_entry(matcher: dict[str, Any], symbol: dict[str, Any], children: list[dict[str, Any]]) -> None:
            symbol_name = str(matcher["symbol"])
            caller_entries.append(
                {
                    "method": "symbol/callers",
                    "matcher": matcher,
                    "result": {
                        "type": "CALLERS_SUCCESS",
                        "ok": True,
                        "query": {
                            "workspaceRoot": ".",
                            "symbol": symbol_name,
                            "fileHint": matcher.get("fileHint"),
                            "kind": matcher.get("kind"),
                            "containingType": matcher.get("containingType"),
                            "direction": "incoming",
                            "depth": 2,
                        },
                        "symbol": symbol,
                        "filePath": symbol["location"]["filePath"],
                        "offset": symbol["location"]["startOffset"],
                        "root": {"symbol": symbol, "callSite": None, "children": children},
                        "stats": {
                            "totalNodes": 1 + len(children),
                            "totalEdges": len(children),
                            "truncatedNodes": 0,
                            "maxDepthReached": 1 if children else 0,
                            "timeoutReached": False,
                            "maxTotalCallsReached": False,
                            "maxChildrenPerNodeReached": False,
                            "filesVisited": len({node["callSite"]["filePath"] for node in children}) if children else 1,
                        },
                        "candidateCount": 1,
                        "alternatives": [],
                        "logFile": ".kast/mock-backend.log",
                    },
                    "provenance": {"source": "bindings", "fallback": True},
                }
            )

        for symbol_name in slot_symbol_variants(function):
            for matcher in symbol_matcher_variants(
                symbol_name,
                kind="FUNCTION",
                file_hint=slot_file(function),
                containing_type=str(function.get("containingType") or "").strip() or None,
            ):
                append_caller_entry(matcher, function_symbol, caller_nodes)
        for caller_name in caller_names:
            caller_symbol = {
                "fqName": str(caller_name),
                "kind": "FUNCTION",
                "location": location(
                    caller_file_from_fq_name(str(caller_name), slot_file(function)),
                    str(caller_name).rsplit(".", 1)[-1],
                ),
                "containingDeclaration": str(caller_name).rsplit(".", 1)[0] if "." in str(caller_name) else None,
            }
            for symbol_name in caller_symbol_variants(caller_name):
                for matcher in symbol_matcher_variants(
                    symbol_name,
                    kind="FUNCTION",
                    file_hint=slot_file(function),
                    containing_type=caller_symbol["containingDeclaration"],
                ):
                    append_caller_entry(matcher, caller_symbol, [])
    return [
        {
            "method": "raw/workspace-files",
            "matcher": {"type": "any"},
            "result": fallback_workspace_files(bindings),
            "provenance": {"source": "bindings", "fallback": True},
        },
        {
            "method": "raw/workspace-symbol",
            "matcher": {"type": "any"},
            "result": {
                "type": "WORKSPACE_SYMBOL_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "pattern": "", "maxResults": 100, "regex": False, "includeDeclarationScope": False},
                "symbols": symbols,
                "page": None,
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        },
        {
            "method": "raw/workspace-search",
            "matcher": {"type": "any"},
            "result": {
                "type": "WORKSPACE_SEARCH_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "pattern": "", "regex": False, "maxResults": 100, "fileGlob": None, "caseSensitive": False},
                "matches": search_matches,
                "truncated": False,
                "schemaVersion": 1,
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        },
        {
            "method": "raw/file-outline",
            "matcher": {"type": "any"},
            "result": {
                "type": "FILE_OUTLINE_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "filePath": slot_file(large_class or member)},
                "symbols": [{"symbol": symbol} for symbol in symbols],
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        },
        {
            "method": "symbol/scaffold",
            "matcher": {"type": "any"},
            "result": {
                "type": "SCAFFOLD_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "targetFile": slot_file(large_class or member), "targetSymbol": None, "mode": "implement", "kind": None},
                "outline": [{"symbol": symbol} for symbol in symbols],
                "fileContent": None,
                "symbol": first_symbol,
                "references": {"locations": references, "count": len(references), "searchScope": scope, "declaration": first_symbol},
                "typeHierarchy": None,
                "insertionPoint": None,
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        },
        *resolve_entries,
        *reference_entries,
        *caller_entries,
        {
            "method": "database/metrics",
            "matcher": {"type": "any"},
            "result": {
                "type": "METRICS_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "metric": "fanIn", "limit": 50, "symbol": None, "depth": 3, "fileGlob": None, "folderFilter": None},
                "results": {"rows": []},
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        },
        {
            "method": "raw/diagnostics",
            "matcher": {"type": "any"},
            "result": {
                "type": "DIAGNOSTICS_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "filePaths": []},
                "clean": True,
                "errorCount": 0,
                "warningCount": 0,
                "infoCount": 0,
                "diagnostics": [],
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        },
    ]


def generate_payload(
    *,
    catalog: dict[str, Any],
    bindings: dict[str, Any],
    history_roots: list[Path],
    generated_at: str | None = None,
) -> dict[str, Any]:
    workspace_text = str(bindings.get("workspace_root") or "").strip()
    workspace_root = Path(workspace_text) if workspace_text else None
    history, rejected = history_entries(history_roots, workspace_root=workspace_root)
    fallback = fallback_entries(bindings)
    fallback_keys = {entry_key(entry) for entry in fallback}
    entries = [entry for entry in history if entry_key(entry) not in fallback_keys]
    all_entries = [*fallback, *entries]
    return {
        "$schema": "https://github.com/amichne/kast/evaluation/mock-backend.schema.json",
        "schema_version": 1,
        "generated_at": generated_at or datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "target_repo": str(bindings.get("target_repo") or ""),
        "workspace_root": workspace_text,
        "catalog_version": int(catalog.get("version", 1) or 1),
        "entries": all_entries,
        "provenance_summary": {
            "history_roots": [str(root) for root in history_roots],
            "history_entry_count": len(entries),
            "fallback_entry_count": len(fallback),
            "rejected_history_entry_count": rejected,
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate runner-local mock KAST backend payloads.")
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--bindings", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--history-root", action="append", default=[], type=Path)
    args = parser.parse_args(argv)

    payload = generate_payload(
        catalog=load_json(args.catalog),
        bindings=load_json(args.bindings),
        history_roots=args.history_root,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + "\n")
    print(f"Generated: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
