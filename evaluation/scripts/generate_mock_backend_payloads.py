#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
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
    if not isinstance(candidate, dict):
        return None
    if "error" in candidate:
        return None
    if isinstance(candidate.get("result"), dict):
        candidate = candidate["result"]
    if candidate.get("ok") is False:
        return None
    return candidate if isinstance(candidate, dict) else None


def matcher_from_args(args: dict[str, Any], workspace_root: Path | None) -> dict[str, Any]:
    matcher = {
        key: canonicalize_paths(value, key=key, workspace_root=workspace_root)
        for key, value in args.items()
        if key in MATCHER_KEYS and value not in (None, "")
    }
    return matcher or {"type": "any"}


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


def history_entries(history_roots: list[Path], *, workspace_root: Path | None) -> tuple[list[dict[str, Any]], int]:
    entries: list[dict[str, Any]] = []
    rejected = 0
    seen: set[str] = set()
    for root in history_roots:
        if not root.exists():
            continue
        for events_path in sorted(root.rglob("sdk-events.jsonl")):
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
                args = starts.get(tool_call_id, {}).get("arguments")
                matcher = matcher_from_args(args if isinstance(args, dict) else {}, workspace_root)
                entry = {
                    "method": method,
                    "matcher": matcher,
                    "result": canonicalize_paths(result, workspace_root=workspace_root),
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


def symbol_from_slot(slot: dict[str, Any], *, kind: str = "CLASS") -> dict[str, Any]:
    symbol = str(slot.get("symbol") or str(slot.get("fqName") or "Mock").split(".")[-1])
    return {
        "fqName": str(slot.get("fqName") or symbol),
        "kind": kind,
        "location": location(slot_file(slot), symbol),
        "containingDeclaration": str(slot.get("containingType") or "").rsplit(".", 1)[0] or None,
    }


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
                "location": location(slot_file(function), str(name).split(".")[-1]),
            },
            "callSite": location(slot_file(function), str(name).split(".")[-1]),
            "children": [],
        }
        for name in caller_names
    ]
    resolve_entries = [
        {
            "method": "symbol/resolve",
            "matcher": {"symbol": symbol["fqName"].split(".")[-1]},
            "result": {
                "type": "RESOLVE_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "symbol": symbol["fqName"].split(".")[-1], "fileHint": None, "kind": None, "containingType": None},
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
        for symbol in symbols
    ] or [
        {
            "method": "symbol/resolve",
            "matcher": {"type": "any"},
            "result": {
                "type": "RESOLVE_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "symbol": first_symbol["fqName"].split(".")[-1], "fileHint": None, "kind": None, "containingType": None},
                "symbol": first_symbol,
                "filePath": first_symbol["location"]["filePath"],
                "offset": first_symbol["location"]["startOffset"],
                "candidate": {"line": first_symbol["location"]["startLine"], "column": first_symbol["location"]["startColumn"], "context": first_symbol["location"]["preview"]},
                "candidateCount": 1,
                "alternatives": [],
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        }
    ]
    reference_entries = []
    for slot in (member, cross_module, slots.get("RENAME_TARGET") if isinstance(slots.get("RENAME_TARGET"), dict) else {}):
        if not isinstance(slot, dict) or not slot.get("symbol"):
            continue
        slot_symbol = symbol_from_slot(slot, kind="PROPERTY" if slot is member else "CLASS")
        expected = slot.get("expected") if isinstance(slot.get("expected"), dict) else {}
        files = (
            expected.get("expectedFiles")
            or expected.get("expectedConsumerFiles")
            or expected.get("affectedFiles")
            or [slot_file(slot)]
        )
        slot_refs = [location(str(file_path), str(slot.get("symbol"))) for file_path in files]
        slot_scope = search_scope(len(slot_refs))
        reference_entries.append(
            {
                "method": "symbol/references",
                "matcher": {"symbol": str(slot.get("symbol"))},
                "result": {
                    "type": "REFERENCES_SUCCESS",
                    "ok": True,
                    "query": {"workspaceRoot": ".", "symbol": str(slot.get("symbol")), "fileHint": None, "kind": None, "containingType": slot.get("containingType"), "includeDeclaration": True},
                    "symbol": slot_symbol,
                    "filePath": slot_symbol["location"]["filePath"],
                    "offset": slot_symbol["location"]["startOffset"],
                    "references": slot_refs,
                    "searchScope": slot_scope,
                    "declaration": slot_symbol,
                    "candidateCount": 1,
                    "alternatives": [],
                    "logFile": ".kast/mock-backend.log",
                },
                "provenance": {"source": "bindings", "fallback": True},
            }
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
    caller_entries = [
        {
            "method": "symbol/callers",
            "matcher": {"symbol": str(function.get("symbol"))},
            "result": {
                "type": "CALLERS_SUCCESS",
                "ok": True,
                "query": {"workspaceRoot": ".", "symbol": str(function.get("symbol")), "direction": "incoming", "depth": 2},
                "symbol": symbol_from_slot(function, kind="FUNCTION"),
                "filePath": slot_file(function),
                "offset": 0,
                "root": {"symbol": symbol_from_slot(function, kind="FUNCTION"), "callSite": None, "children": caller_nodes},
                "stats": {
                    "totalNodes": 1 + len(caller_nodes),
                    "totalEdges": len(caller_nodes),
                    "truncatedNodes": 0,
                    "maxDepthReached": 1 if caller_nodes else 0,
                    "timeoutReached": False,
                    "maxTotalCallsReached": False,
                    "maxChildrenPerNodeReached": False,
                    "filesVisited": len({node["callSite"]["filePath"] for node in caller_nodes}) if caller_nodes else 1,
                },
                "candidateCount": 1,
                "alternatives": [],
                "logFile": ".kast/mock-backend.log",
            },
            "provenance": {"source": "bindings", "fallback": True},
        }
    ] if isinstance(function, dict) and function.get("symbol") else []
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
    entries, rejected = history_entries(history_roots, workspace_root=workspace_root)
    existing_keys = {entry_key(entry) for entry in entries}
    fallback = [entry for entry in fallback_entries(bindings) if entry_key(entry) not in existing_keys]
    all_entries = [*entries, *fallback]
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
