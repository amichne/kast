#!/usr/bin/env python3
"""Public CLI corruption proof over an explicitly selected isolated runtime.

The state receipt is cache-identity.properties v3, owned by SidecarCacheLifecycle.
Its exact directory comes from passive status, never a filesystem search.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from enum import Enum

from release_upgrade_acceptance import identity, workspace_identity


class Cause(str, Enum):
    REJECTION_UNPROVEN = "rejection-unproven"
    CONTINUATION_UNPROVEN = "continuation-unproven"
    RUNTIME_UNPROVEN = "runtime-unproven"
    STATE_AUTHORITY_REJECTED = "state-authority-rejected"
    STATE_RESTORATION_FAILED = "state-restoration-failed"
    RECOVERY_UNPROVEN = "recovery-unproven"
    WORKSPACE_CHANGED = "workspace-changed"
    COMMAND_FAILED = "command-failed"
    COMMAND_BUDGET_EXCEEDED = "command-budget-exceeded"


class Family(str, Enum):
    RELATION = "relation"
    TRAVERSAL = "traversal"

    def arguments(self, selector):
        if self is Family.RELATION:
            return ["relation", "read", "--selector", selector, "--relation", "callees", "--limit", "1"]
        return ["traversal", "run", "--selector", selector, "--relation", "callees", "--maximum-depth", "3", "--maximum-results", "1"]

    @property
    def operation(self):
        return "relation.read" if self is Family.RELATION else "traversal.run"


class SemanticCorruptionFailure(Exception):
    def __init__(self, cause):
        self.cause = cause
        super().__init__(cause.value)


def admit_boundary_rejection(result, boundary, reason, exit_code, diagnostic=None):
    if result.returncode != exit_code or result.stdout.strip():
        raise SemanticCorruptionFailure(Cause.REJECTION_UNPROVEN)
    try:
        document = json.loads(result.stderr)
    except (ValueError, UnicodeError) as failure:
        raise SemanticCorruptionFailure(Cause.REJECTION_UNPROVEN) from failure
    keys = {"status", "boundary", "reason"} | ({"diagnostic"} if diagnostic else set())
    if not isinstance(document, dict) or set(document) != keys or document.get("status") != "rejected" or document.get("boundary") != boundary or document.get("reason") != reason:
        raise SemanticCorruptionFailure(Cause.REJECTION_UNPROVEN)
    if diagnostic is not None and (
        not isinstance(document.get("diagnostic"), str)
        or document["diagnostic"].splitlines()[-1:] != ["Error: " + diagnostic]
    ):
        raise SemanticCorruptionFailure(Cause.REJECTION_UNPROVEN)
    return {"exitCode": exit_code, "boundary": boundary, "reason": reason, "documentDigest": identity(document)}


def rejected_command(acceptance, arguments):
    try:
        result = subprocess.run([str(acceptance.executable), *arguments], cwd=acceptance.workspace,
                                env=acceptance.environment, text=True, capture_output=True,
                                timeout=acceptance.maximum_operation_seconds, check=False)
    except subprocess.TimeoutExpired as failure:
        raise SemanticCorruptionFailure(Cause.COMMAND_BUDGET_EXCEEDED) from failure
    except OSError as failure:
        raise SemanticCorruptionFailure(Cause.COMMAND_FAILED) from failure
    if len(result.stdout.encode()) + len(result.stderr.encode()) > acceptance.maximum_output_bytes:
        raise SemanticCorruptionFailure(Cause.COMMAND_BUDGET_EXCEEDED)
    return result


def admitted_continuation(document, family: Family):
    if not isinstance(document, dict) or document.get("operation") != family.operation or document.get("status") != "qualified":
        raise SemanticCorruptionFailure(Cause.CONTINUATION_UNPROVEN)
    qualification = document.get("qualification")
    if not isinstance(qualification, dict) or qualification.get("type") != "resumable":
        raise SemanticCorruptionFailure(Cause.CONTINUATION_UNPROVEN)
    token = qualification.get("continuation")
    if not isinstance(token, str) or not 1 <= len(token.encode()) <= 65_536:
        raise SemanticCorruptionFailure(Cause.CONTINUATION_UNPROVEN)
    parts = token.split(":")
    if len(parts) != 4 or parts[:2] != [family.value + "-continuation", "v1"]:
        raise SemanticCorruptionFailure(Cause.CONTINUATION_UNPROVEN)
    try:
        payload = base64.b64decode(parts[2] + "=" * (-len(parts[2]) % 4), altchars=b"-_", validate=True)
        payload.decode("utf-8")
    except (ValueError, UnicodeError) as failure:
        raise SemanticCorruptionFailure(Cause.CONTINUATION_UNPROVEN) from failure
    if not payload or base64.urlsafe_b64encode(payload).decode().rstrip("=") != parts[2] or hashlib.sha256(payload).hexdigest() != parts[3]:
        raise SemanticCorruptionFailure(Cause.CONTINUATION_UNPROVEN)
    return token


def prove_continuations(acceptance, selector):
    observations = []
    for family in Family:
        arguments = family.arguments(selector)
        first = acceptance.command(*arguments)
        token = admitted_continuation(first, family)
        resumed = acceptance.command(*arguments, "--continuation", token)
        if resumed.get("operation") != family.operation or resumed.get("status") not in {"complete", "qualified"}:
            raise SemanticCorruptionFailure(Cause.CONTINUATION_UNPROVEN)
        parts = token.split(":")
        parts[3] = ("1" if parts[3][0] == "0" else "0") + parts[3][1:]
        for case, corrupted in [("malformed", family.value + "-continuation:v1:!"),
                                ("digest-tampered", ":".join(parts))]:
            result = rejected_command(acceptance, [*arguments, "--continuation", corrupted])
            observed = admit_boundary_rejection(result, "usage", "arguments-rejected", 2,
                f"--continuation must be one intact {family.value} continuation token")
            observations.append({"family": family.value, "case": case, "status": "rejected", **observed,
                                 "originalContinuationDigest": identity(token),
                                 "validResumeDigest": identity(resumed)})
    return observations


def running_status(document, host):
    cache = document.get("cache") if isinstance(document, dict) else None
    if not isinstance(document, dict) or document.get("command") != "status" or document.get("status") != "complete" or document.get("runtime") != "running" or document.get("root") != str(host.workspace) or not isinstance(cache, dict):
        raise SemanticCorruptionFailure(Cause.RUNTIME_UNPROVEN)
    key = cache.get("identity")
    runtime = document.get("runtimeId")
    if not isinstance(runtime, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", runtime):
        raise SemanticCorruptionFailure(Cause.RUNTIME_UNPROVEN)
    if not isinstance(key, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", key):
        raise SemanticCorruptionFailure(Cause.STATE_AUTHORITY_REJECTED)
    return key


def selected_receipt(acceptance, host, key):
    cache_root = host.runtime / "intellij-caches"
    if acceptance.workspace != host.workspace or acceptance.environment.get("KAST_CACHE_ROOT") != str(cache_root):
        raise SemanticCorruptionFailure(Cause.STATE_AUTHORITY_REJECTED)
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", key):
        raise SemanticCorruptionFailure(Cause.STATE_AUTHORITY_REJECTED)
    path = cache_root / key / "cache-identity.properties"
    try:
        for selected in (host.root, host.runtime, cache_root, path.parent, path):
            if selected.is_symlink() or selected.resolve(strict=True) != selected:
                raise SemanticCorruptionFailure(Cause.STATE_AUTHORITY_REJECTED)
        path.relative_to(host.root)
        observed = path.stat()
        if not stat.S_ISREG(observed.st_mode) or not 1 <= observed.st_size <= 16_384:
            raise SemanticCorruptionFailure(Cause.STATE_AUTHORITY_REJECTED)
        original = path.read_bytes()
    except (OSError, ValueError) as failure:
        raise SemanticCorruptionFailure(Cause.STATE_AUTHORITY_REJECTED) from failure
    if not re.search(rb"(?m)^format=kast\.sidecar-cache\.identity\.v3\r?$", original):
        raise SemanticCorruptionFailure(Cause.STATE_AUTHORITY_REJECTED)
    return path, original, stat.S_IMODE(observed.st_mode)


def replace_receipt(path, contents, mode):
    descriptor, raw = tempfile.mkstemp(prefix=".corruption-proof-", dir=path.parent)
    temporary = Path(raw)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(contents)
            output.flush()
            os.fchmod(output.fileno(), mode)
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def prove_state_receipt(acceptance, host, selector, status, workspace_digest):
    key = running_status(status, host)
    path, original, mode = selected_receipt(acceptance, host, key)
    try:
        replace_receipt(path, b"format=corrupted-runtime-identity\n", mode)
        result = rejected_command(acceptance, ["status"])
        observed = admit_boundary_rejection(result, "runtime", "status-cache-invalid-identity", 4)
        if workspace_identity(host.workspace) != workspace_digest:
            raise SemanticCorruptionFailure(Cause.WORKSPACE_CHANGED)
    finally:
        try:
            replace_receipt(path, original, mode)
            if path.is_symlink() or path.read_bytes() != original or stat.S_IMODE(path.stat().st_mode) != mode:
                raise SemanticCorruptionFailure(Cause.STATE_RESTORATION_FAILED)
        except OSError as failure:
            raise SemanticCorruptionFailure(Cause.STATE_RESTORATION_FAILED) from failure
    restored = acceptance.command("status")
    if running_status(restored, host) != key or restored.get("runtimeId") != status.get("runtimeId"):
        raise SemanticCorruptionFailure(Cause.RECOVERY_UNPROVEN)
    read = acceptance.command("source", "read", "--anchor", selector)
    if read.get("operation") != "source.read" or read.get("status") not in {"complete", "qualified"}:
        raise SemanticCorruptionFailure(Cause.RECOVERY_UNPROVEN)
    if workspace_identity(host.workspace) != workspace_digest:
        raise SemanticCorruptionFailure(Cause.WORKSPACE_CHANGED)
    return {"kind": "cache-identity-v3", "status": "rejected-and-restored", **observed,
            "cacheIdentity": key, "originalReceiptDigest": "sha256:" + hashlib.sha256(original).hexdigest(),
            "restoredReceiptDigest": "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest(),
            "restoredMode": mode, "recoveredStatusDigest": identity(restored), "recoveredReadDigest": identity(read)}


def prove_semantic_corruption(acceptance, host):
    before = workspace_identity(host.workspace)
    status = acceptance.command("status")
    running_status(status, host)
    topology = acceptance.command("topology", "build", timeout=acceptance.maximum_startup_seconds)
    if topology.get("operation") != "topology.build" or topology.get("status") != "complete":
        raise SemanticCorruptionFailure(Cause.CONTINUATION_UNPROVEN)
    symbols = acceptance.resolve_symbols("enterpriseRootOperation", 16)
    selected = [selector for selector, symbol in symbols.items() if symbol.get("name") == "enterpriseRootOperation"]
    if len(selected) != 1 or not isinstance(selected[0], str) or not selected[0]:
        raise SemanticCorruptionFailure(Cause.RUNTIME_UNPROVEN)
    selector = selected[0]
    continuations = prove_continuations(acceptance, selector)
    state = prove_state_receipt(acceptance, host, selector, status, before)
    after = workspace_identity(host.workspace)
    if after != before:
        raise SemanticCorruptionFailure(Cause.WORKSPACE_CHANGED)
    return {"schemaVersion": 1, "status": "passed", "continuations": continuations,
            "stateReceipt": state, "workspaceDigestBefore": before, "workspaceDigestAfter": after}
