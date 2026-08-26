"""Zensical macros that project the generated Kast operation graph into pages."""

from __future__ import annotations

import json
import posixpath
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
GRAPH_PATH = ROOT / "docs" / "_data" / "kast-docs.json"


def _load_graph() -> dict[str, Any]:
    return json.loads(GRAPH_PATH.read_text(encoding="utf-8"))


def _relative_link(source_path: str, target_path: str) -> str:
    source_directory = posixpath.dirname(str(source_path)) or "."
    return posixpath.relpath(target_path, source_directory)


def define_env(env: Any) -> None:
    graph = _load_graph()
    env.variables["kast_product_version"] = graph["productVersion"]
    env.variables["kast_runtime_id"] = graph["runtimeId"]

    @env.macro
    def kast_contract_links(metadata: Any, source_path: str) -> str:
        """Render exact CLI contracts named by one page's front matter."""
        meta = dict(metadata or {})
        operation_ids = list(meta.get("kast_operations") or [])
        lifecycle_commands = list(meta.get("kast_lifecycle_commands") or [])
        if not operation_ids and not lifecycle_commands:
            return ""

        operation_reference = _relative_link(source_path, "reference/operations.md")
        cli_reference = _relative_link(source_path, "reference/cli.md")
        lines = ['!!! info "CLI contract"']

        for operation_id in operation_ids:
            operation = graph["operations"].get(operation_id)
            if operation is None:
                raise ValueError(f"Unknown Kast operation in page metadata: {operation_id}")
            lines.append(
                "    - "
                f"[`{operation_id}`]({operation_reference}#{operation['anchor']}) — "
                f"`kast {operation['command']}`"
            )

        for command in lifecycle_commands:
            lifecycle = graph["lifecycle"].get(command)
            if lifecycle is None:
                raise ValueError(f"Unknown Kast lifecycle command in page metadata: {command}")
            lines.append(
                "    - "
                f"[`kast {command}`]({cli_reference}#{lifecycle['anchor']}) — "
                f"{lifecycle['description']}"
            )
        return "\n".join(lines)
