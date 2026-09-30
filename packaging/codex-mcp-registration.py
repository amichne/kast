#!/usr/bin/env python3
"""Register the installed Kast MCP command without replacing another owner's entry."""

import json
import importlib.util
import os
import subprocess
import sys
from pathlib import Path


def observed_registration(name: str):
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


def admitted_legacy_command(root: Path, command: str) -> bool:
    """Read-only migration preflight; native connect owns the later replacement."""
    if command != str(root / 'current/bin/kast-mcp-complete'):
        return False
    selector = root / 'current'
    if not selector.is_symlink() or selector.lstat().st_uid != os.getuid():
        return False
    target = selector.readlink()
    if target.is_absolute() or len(target.parts) != 2 or target.parts[0] != 'versions':
        return False
    spec = importlib.util.spec_from_file_location('kast_mcp_migration_lifecycle', Path(__file__).with_name('installation-lifecycle.py'))
    lifecycle = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = lifecycle
    spec.loader.exec_module(lifecycle)
    try:
        installation = lifecycle.Installation.admit(str(root / target))
        lifecycle.require_selected(installation)
        return installation.manifest['schemaVersion'] in (1, 2)
    except (lifecycle.Rejected, OSError, ValueError, TypeError, KeyError):
        return False


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in {"check", "install", "uninstall"}:
        return 64
    action, root = sys.argv[1], Path(sys.argv[2])
    command = str(root / "installation" / "bin" / "kast-mcp-complete")
    try:
        configured = observed_registration("kast")
    except (ValueError, json.JSONDecodeError):
        print("kast-install: Codex MCP configuration could not be inspected", file=sys.stderr)
        return 69
    if configured is not None:
        transport = configured.get("transport", {})
        owned = isinstance(transport, dict) and transport.get("type") == "stdio" and transport.get("command") == command and transport.get("args") == []
        legacy = (action == 'check' and isinstance(transport, dict) and transport.get('type') == 'stdio'
                  and transport.get('args') == [] and isinstance(transport.get('command'), str)
                  and admitted_legacy_command(root, transport['command']))
        if not owned and not legacy:
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
