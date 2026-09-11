"""Client for the optional test-only plugin in an isolated native acceptance IDE."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import time
import uuid

COMMANDS = frozenset({"OBSERVE", "DIRTY_UNCOMMITTED", "COMMIT_DOCUMENT", "RESTORE_SAVED", "UNDO_PRODUCTION_CHANGE", "ARM_POST_SAVE_BARRIER", "UNLOAD_PRODUCTION_PLUGIN"})
DIGEST = re.compile(r"[0-9a-f]{64}\Z")
FAILURES = frozenset({"NOT_ENABLED", "SANDBOX_REJECTED", "PROJECT_MISMATCH", "SPOOL_REJECTED", "REQUEST_TOO_LARGE", "MALFORMED_REQUEST", "REQUEST_ID_MISMATCH", "UNKNOWN_COMMAND", "IMAGE_GUARD_REQUIRED", "TARGET_UNAVAILABLE", "SOURCE_TOO_LARGE", "SOURCE_ENCODING_REJECTED", "SAVED_IMAGE_CHANGED", "DOCUMENT_IMAGE_CHANGED", "DOCUMENT_STATE_REJECTED", "PSI_UNAVAILABLE", "PSI_LIMIT_EXCEEDED", "UNDO_UNAVAILABLE", "UNDO_COMMAND_MISMATCH", "UNDO_CONFIRMATION_REQUIRED", "UNDO_IMAGE_MISMATCH", "SAVE_REJECTED", "NATIVE_UNAVAILABLE", "RESPONSE_UNAVAILABLE", "BARRIER_ALREADY_ARMED", "BARRIER_IMAGE_MISMATCH", "PLUGIN_UNAVAILABLE", "PLUGIN_UNLOAD_UNSUPPORTED", "PLUGIN_UNLOAD_REJECTED"})


class NativeFixtureProbeError(RuntimeError):
    """A bounded local fixture transport or protocol failure; contains no payload."""


class NativeFixtureProbe:
    def __init__(self, root: Path | str, project: Path | str):
        self.root = Path(root).resolve(strict=True)
        self.project = Path(project).resolve(strict=True)
        if self.project != self.root / "workspace" or self.root.stat().st_mode & 0o777 != 0o700:
            raise NativeFixtureProbeError("SANDBOX_REJECTED")
        self.spool = self.root / "native-probe"

    def request(self, command: str, expected_preimage_sha256: str,
                expected_postimage_sha256: str | None = None, timeout: float = 30) -> dict:
        if command not in COMMANDS or not DIGEST.fullmatch(expected_preimage_sha256):
            raise NativeFixtureProbeError("MALFORMED_REQUEST")
        if expected_postimage_sha256 is not None and (not DIGEST.fullmatch(expected_postimage_sha256) or expected_postimage_sha256 == expected_preimage_sha256):
            raise NativeFixtureProbeError("IMAGE_GUARD_REQUIRED")
        if command in {"UNDO_PRODUCTION_CHANGE", "ARM_POST_SAVE_BARRIER"} and expected_postimage_sha256 is None:
            raise NativeFixtureProbeError("IMAGE_GUARD_REQUIRED")
        if not 0 < timeout <= 300:
            raise NativeFixtureProbeError("MALFORMED_REQUEST")
        deadline = time.monotonic() + timeout
        ready = self._wait(self.spool / "ready.json", deadline)
        if ready != {"version": 1, "outcome": "READY"}:
            raise NativeFixtureProbeError("MALFORMED_RESPONSE")
        for directory in (self.spool, self.spool / "requests", self.spool / "responses"):
            if directory.resolve(strict=True) != directory or directory.stat().st_mode & 0o777 != 0o700:
                raise NativeFixtureProbeError("SPOOL_REJECTED")
        request_id = str(uuid.uuid4())
        body = {"version": 1, "id": request_id, "command": command,
                "expectedPreimageSha256": expected_preimage_sha256}
        if expected_postimage_sha256 is not None:
            body["expectedPostimageSha256"] = expected_postimage_sha256
        target = self.spool / "requests" / f"{request_id}.json"
        temporary = target.with_suffix(".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(json.dumps(body, separators=(",", ":"), ensure_ascii=True).encode("utf-8"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
        result = self._wait(self.spool / "responses" / f"{request_id}.json", deadline)
        return validate_response(result, request_id, command)

    def await_save_barrier(self, barrier_id: str, expected_preimage_sha256: str,
                           expected_postimage_sha256: str, timeout: float = 30) -> dict:
        try:
            canonical = str(uuid.UUID(barrier_id))
        except (ValueError, TypeError, AttributeError):
            raise NativeFixtureProbeError("MALFORMED_REQUEST") from None
        if canonical != barrier_id or not 0 < timeout <= 300:
            raise NativeFixtureProbeError("MALFORMED_REQUEST")
        if not DIGEST.fullmatch(expected_preimage_sha256) or not DIGEST.fullmatch(expected_postimage_sha256) or expected_preimage_sha256 == expected_postimage_sha256:
            raise NativeFixtureProbeError("IMAGE_GUARD_REQUIRED")
        directory = self.spool / "barriers"
        result = self._wait(directory / f"{barrier_id}.json", time.monotonic() + timeout)
        validated = validate_barrier(result, barrier_id, expected_preimage_sha256, expected_postimage_sha256)
        if (directory / f"{barrier_id}.released.json").exists():
            raise NativeFixtureProbeError("BARRIER_ALREADY_RELEASED")
        return validated

    def _wait(self, path: Path, deadline: float) -> dict:
        while time.monotonic() < deadline:
            if path.exists():
                if path.is_symlink() or path.stat().st_mode & 0o777 != 0o600:
                    raise NativeFixtureProbeError("SPOOL_REJECTED")
                with path.open("rb") as source:
                    raw = source.read(32769)
                if len(raw) > 32768:
                    raise NativeFixtureProbeError("MALFORMED_RESPONSE")
                try:
                    return json.loads(raw)
                except (ValueError, UnicodeError):
                    raise NativeFixtureProbeError("MALFORMED_RESPONSE") from None
            time.sleep(min(0.05, max(0, deadline - time.monotonic())))
        raise NativeFixtureProbeError("PROBE_TIMEOUT")


def validate_response(result: object, request_id: str, command: str) -> dict:
    if not isinstance(result, dict) or result.get("version") != 1 or result.get("id") != request_id or result.get("command") != command:
        raise NativeFixtureProbeError("RESPONSE_CORRELATION_REJECTED")
    outcome = result.get("outcome")
    if outcome in {"REJECTED", "EFFECT_UNCERTAIN"}:
        if set(result) != {"version", "id", "command", "outcome", "failure"} or result["failure"] not in FAILURES:
            raise NativeFixtureProbeError("MALFORMED_RESPONSE")
        return result
    fields = {"version", "id", "command", "outcome", "evidence"}
    if command == "ARM_POST_SAVE_BARRIER":
        fields.add("barrierId")
        if outcome != "BARRIER_ARMED" or result.get("barrierId") != request_id:
            raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    elif command == "UNLOAD_PRODUCTION_PLUGIN":
        fields.add("lifecycle")
        if outcome != "LIFECYCLE_COMPLETED" or result.get("lifecycle") != "UNLOADED":
            raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    elif outcome != "COMPLETED":
        raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    if set(result) != fields:
        raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    evidence = result["evidence"]
    if not isinstance(evidence, dict) or set(evidence) != {"savedSha256", "documentSha256", "documentState", "syntax", "undo", "declarations"}:
        raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    for key in ("savedSha256", "documentSha256"):
        if not isinstance(evidence[key], str) or not DIGEST.fullmatch(evidence[key]):
            raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    if evidence["documentState"] not in {"SAVED_COMMITTED", "SAVED_UNCOMMITTED", "DIRTY_COMMITTED", "DIRTY_UNCOMMITTED"} or evidence["syntax"] not in {"CLEAN", "ERRORS", "UNCOMMITTED"} or evidence["undo"] not in {"PRODUCTION_CHANGE", "OTHER", "UNAVAILABLE"}:
        raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    declarations = evidence["declarations"]
    if not isinstance(declarations, list) or len(declarations) > 64:
        raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    for declaration in declarations:
        if not isinstance(declaration, dict) or set(declaration) != {"name", "container", "kind"}:
            raise NativeFixtureProbeError("MALFORMED_RESPONSE")
        if not isinstance(declaration["name"], str) or len(declaration["name"]) > 128 or not isinstance(declaration["container"], str) or len(declaration["container"]) > 512 or declaration["kind"] not in {"CLASS", "OBJECT", "FUNCTION", "PROPERTY", "TYPE_ALIAS", "OTHER"}:
            raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    return result


def validate_barrier(result: object, barrier_id: str, preimage: str, postimage: str) -> dict:
    if not isinstance(result, dict) or result.get("version") != 1 or result.get("id") != barrier_id or result.get("command") != "ARM_POST_SAVE_BARRIER":
        raise NativeFixtureProbeError("RESPONSE_CORRELATION_REJECTED")
    if result.get("outcome") == "REJECTED" and set(result) == {"version", "id", "command", "outcome", "failure"} and result["failure"] in FAILURES:
        raise NativeFixtureProbeError(result["failure"])
    if set(result) != {"version", "id", "command", "outcome", "expectedPreimageSha256", "expectedPostimageSha256", "savedSha256", "documentSha256", "documentState", "maximumWaitMillis"}:
        raise NativeFixtureProbeError("MALFORMED_RESPONSE")
    if result["outcome"] != "REACHED" or result["expectedPreimageSha256"] != preimage or result["expectedPostimageSha256"] != postimage or result["savedSha256"] != postimage or result["documentSha256"] != postimage or result["documentState"] != "SAVED_COMMITTED" or result["maximumWaitMillis"] != 10000:
        raise NativeFixtureProbeError("BARRIER_IMAGE_MISMATCH")
    return result
