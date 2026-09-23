#!/usr/bin/env python3
"""Register the installed Kast MCP command without replacing another owner's entry."""

import json
import subprocess
import sys
from pathlib import Path


def current(name: str):
    result = subprocess.run(["codex", "mcp", "list", "--json"], capture_output=True, text=True)
    if result.returncode != 0:
        raise ValueError("Codex MCP configuration is unavailable")
    servers = json.loads(result.stdout)
    if not isinstance(servers, list):
        raise ValueError("Codex MCP configuration is malformed")
    matches = [server for server in servers if isinstance(server, dict) and server.get("name") == name]
    if len(matches) > 1:
        raise ValueError("Codex MCP name is ambiguous")
    return matches[0] if matches else None


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in {"check", "install", "uninstall"}:
        return 64
    action, root = sys.argv[1], Path(sys.argv[2])
    command = str(root / "current" / "bin" / "kast-mcp-complete")
    try:
        configured = current("kast")
    except (ValueError, json.JSONDecodeError):
        print("kast-install: Codex MCP configuration could not be inspected", file=sys.stderr)
        return 69
    if configured is not None:
        transport = configured.get("transport", {})
        owned = isinstance(transport, dict) and transport.get("type") == "stdio" and transport.get("command") == command and transport.get("args") == []
        if not owned:
            if action == "uninstall":
                return 0
            print("kast-install: Codex MCP name 'kast' belongs to another configuration", file=sys.stderr)
            return 65
    if action == "check":
        return 0
    if action == "install":
        if configured is None:
            result = subprocess.run(["codex", "mcp", "add", "kast", "--", command], capture_output=True, text=True)
            if result.returncode != 0:
                print("kast-install: Codex MCP registration failed", file=sys.stderr)
                return 69
    elif configured is not None:
        result = subprocess.run(["codex", "mcp", "remove", "kast"], capture_output=True, text=True)
        if result.returncode != 0:
            print("kast-install: Codex MCP removal failed", file=sys.stderr)
            return 69
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
