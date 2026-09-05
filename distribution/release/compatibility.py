#!/usr/bin/env python3
"""Capture and compare the public Kast contract; no publication or orchestration lives here."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import tempfile
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any


class Cause(str, Enum):
    INVALID_DOCUMENT = "invalid-document"
    SOURCE_IDENTITY_MISMATCH = "source-identity-mismatch"
    SOURCE_DIRTY = "source-dirty"
    RELEASE_LOOKUP_FAILED = "release-lookup-failed"
    BASELINE_UNAVAILABLE = "baseline-unavailable"
    BASELINE_IDENTITY_MISMATCH = "baseline-identity-mismatch"
    BREAKING_CHANGE = "breaking-change"
    VERSION_NOT_FORWARD = "version-not-forward"
    MAJOR_CHANGE_NOT_AUTHORIZED = "major-change-not-authorized"
    FIRST_STABLE_VERSION_INVALID = "first-stable-version-invalid"


class Rejected(Exception):
    def __init__(self, cause: Cause, changes: tuple[str, ...] = ()):
        self.cause = cause
        self.changes = changes
        super().__init__(cause.value)


@dataclass(frozen=True, order=True)
class Version:
    major: int
    minor: int
    patch: int

    @classmethod
    def parse(cls, raw: str) -> Version:
        if not isinstance(raw, str) or not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", raw):
            raise Rejected(Cause.INVALID_DOCUMENT)
        return cls(*(int(piece) for piece in raw.split(".")))

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def digest(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical(value) + b"\n")


def read(path: Path) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
        raise Rejected(Cause.INVALID_DOCUMENT)
    try:
        document = json.loads(path.read_bytes())
    except (ValueError, UnicodeError) as failure:
        raise Rejected(Cause.INVALID_DOCUMENT) from failure
    if not isinstance(document, dict):
        raise Rejected(Cause.INVALID_DOCUMENT)
    return document


def run(command: list[str], *, cwd: Path | None = None) -> str:
    try:
        result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as failure:
        raise Rejected(Cause.RELEASE_LOOKUP_FAILED) from failure
    if result.returncode:
        raise Rejected(Cause.RELEASE_LOOKUP_FAILED)
    return result.stdout


def snapshot(document: dict) -> dict:
    required = {"schemaVersion", "productVersion", "sourceRevision", "contract", "inputs"}
    if set(document) != required or document["schemaVersion"] != 1:
        raise Rejected(Cause.INVALID_DOCUMENT)
    Version.parse(document["productVersion"])
    if not isinstance(document["sourceRevision"], str) or not re.fullmatch(r"[0-9a-f]{40}", document["sourceRevision"]):
        raise Rejected(Cause.INVALID_DOCUMENT)
    inputs = document["inputs"]
    if not isinstance(inputs, dict) or set(inputs) != {"schemaDigest", "stateManifestDigest"} or not all(
        isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", value) for value in inputs.values()
    ):
        raise Rejected(Cause.INVALID_DOCUMENT)
    contract = document["contract"]
    if not isinstance(contract, dict) or set(contract) != {"schema", "commands", "persistedState", "processContract"}:
        raise Rejected(Cause.INVALID_DOCUMENT)
    if not all(isinstance(contract[key], dict) and contract[key] for key in contract):
        raise Rejected(Cause.INVALID_DOCUMENT)
    for owner in contract["persistedState"].values():
        if not isinstance(owner, dict) or not owner or not all(
            isinstance(path, str) and path and not Path(path).is_absolute() and ".." not in Path(path).parts
            and isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", value) for path, value in owner.items()
        ):
            raise Rejected(Cause.INVALID_DOCUMENT)
    for command in contract["commands"].values():
        if not isinstance(command, dict) or set(command) != {"options"} or not isinstance(command["options"], list) or not command["options"] or not all(
            isinstance(option, str) and option.startswith("-") for option in command["options"]
        ):
            raise Rejected(Cause.INVALID_DOCUMENT)
    schema = contract["schema"]
    if set(schema) != {"schemaVersion", "wireSchema", "operationRegistry", "cliProjection", "serverProjection"}:
        raise Rejected(Cause.INVALID_DOCUMENT)
    for owner, key, identity in [(schema["operationRegistry"], "operations", "operationId"), (schema["serverProjection"], "tools", "name")]:
        if not isinstance(owner, dict) or not isinstance(owner.get(key), list) or not owner[key]:
            raise Rejected(Cause.INVALID_DOCUMENT)
        names = [item.get(identity) for item in owner[key] if isinstance(item, dict)]
        if len(names) != len(owner[key]) or not all(isinstance(name, str) and name for name in names) or len(set(names)) != len(names):
            raise Rejected(Cause.INVALID_DOCUMENT)
    return document


def without_annotations(value: Any, *, schema_context: bool = False, property_map: bool = False) -> Any:
    if isinstance(value, dict):
        annotations = {"description", "title", "examples", "$comment"} if schema_context and not property_map else set()
        if not schema_context and ("operationId" in value or {"name", "inputSchema", "outputSchema"} <= value.keys()):
            annotations = {"description"}
        return {
            key: without_annotations(
                item,
                schema_context=schema_context or key in {"inputSchema", "outputSchema", "wireSchema"},
                property_map=schema_context and not property_map and key in {"properties", "$defs", "definitions", "patternProperties"},
            )
            for key, item in value.items() if key not in annotations
        }
    if isinstance(value, list):
        return [without_annotations(item, schema_context=schema_context) for item in value]
    return value


def differences(previous: Any, candidate: Any, path: str = "contract") -> list[str]:
    """Conservative subset proof: unknown schema changes reject instead of guessing compatibility."""
    if isinstance(previous, dict) and isinstance(candidate, dict):
        changes = []
        for key, value in sorted(previous.items()):
            if key not in candidate:
                changes.append(path + "/" + key + ":removed")
            else:
                changes.extend(differences(value, candidate[key], path + "/" + key))
        # Additional schema validation constraints or emitted properties may break closed readers.
        if ("/inputSchema" in path or "/outputSchema" in path or "/wireSchema" in path) and set(candidate) - set(previous):
            changes.append(path + ":unproven-addition")
        return changes
    if isinstance(previous, list) and isinstance(candidate, list):
        if path.endswith("/tools"):
            return differences({x["name"]: x for x in previous}, {x["name"]: x for x in candidate}, path)
        if path.endswith("/operations"):
            return differences({x["operationId"]: x for x in previous}, {x["operationId"]: x for x in candidate}, path)
        if "/cliProjection/" in path or path.endswith("/options"):
            return [] if all(item in candidate for item in previous) else [path + ":removed-or-changed"]
    return [] if previous == candidate else [path + ":changed"]


def compare(previous: dict, candidate: dict, *, allow_next_major: bool = False) -> dict:
    previous, candidate = snapshot(previous), snapshot(candidate)
    before, after = Version.parse(previous["productVersion"]), Version.parse(candidate["productVersion"])
    if after <= before:
        raise Rejected(Cause.VERSION_NOT_FORWARD)
    changes = differences(without_annotations(previous["contract"]), without_annotations(candidate["contract"]))
    if after.major != before.major:
        if after.major != before.major + 1 or not allow_next_major:
            raise Rejected(Cause.MAJOR_CHANGE_NOT_AUTHORIZED)
        status = "next-major"
    elif changes:
        raise Rejected(Cause.BREAKING_CHANGE, tuple(changes))
    else:
        status = "compatible"
    return {"status": status, "baselineVersion": str(before), "candidateVersion": str(after), "changes": changes}


def latest_stable(releases: list[dict], major: int | None = None) -> dict | None:
    candidates = []
    for release in releases:
        if not isinstance(release, dict):
            raise Rejected(Cause.RELEASE_LOOKUP_FAILED)
        if release.get("draft") is not False or release.get("prerelease") is not False:
            continue
        tag = release.get("tag_name", "")
        if not isinstance(tag, str) or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
            continue
        try:
            version = Version.parse(tag[1:])
        except Rejected:
            continue
        if version.major >= 1 and (major is None or version.major == major):
            candidates.append((version, release))
    return max(candidates, key=lambda item: item[0])[1] if candidates else None


def command_surface(kast: Path, schema: dict, root: Path) -> dict:
    commands = {(), ("start",), ("stop",), ("status",)}
    for tool in schema["serverProjection"]["tools"]:
        words = tool["invocation"]["command"]
        if not isinstance(words, list) or not words or not all(isinstance(word, str) and re.fullmatch(r"[a-z-]+", word) for word in words):
            raise Rejected(Cause.INVALID_DOCUMENT)
        commands.add(tuple(words))
    commands.update(tuple(command.split()) for command in schema["cliProjection"]["localCommands"])
    result = {}
    for command in sorted(commands):
        help_text = run([str(kast), *command, "--help"], cwd=root)
        # Read parser-rendered option spellings/arity, excluding prose that is not a contract.
        options = []
        for line in help_text.splitlines():
            if re.match(r"^\s+-[-a-z]", line):
                options.append(re.split(r"\s{2,}", line.strip(), maxsplit=1)[0])
        if not options or "--help" not in " ".join(options):
            raise Rejected(Cause.INVALID_DOCUMENT)
        result[" ".join(command) or "kast"] = {"options": sorted(set(options))}
    return result


def process_surface(root: Path, manifest: dict) -> dict:
    """Project finite exit variants from their Kotlin owner, checking the promised manifest."""
    source = (root / "cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt").read_text()
    enum = re.search(r"enum class CliBoundaryExitStatus\(.*?\n\) \{(.*?)\n\}", source, re.DOTALL)
    if enum is None:
        raise Rejected(Cause.INVALID_DOCUMENT)
    exits = {name: int(code) for name, code in re.findall(r"([A-Z_]+)\(([0-9]+)\)", enum[1])}
    process = dict(manifest["processContract"])
    if exits != process["boundaryExits"]:
        raise Rejected(Cause.SOURCE_IDENTITY_MISMATCH)
    for variant, key in [("Complete", "successExit"), ("Qualified", "qualifiedExit"), ("OperationRejected", "semanticRejectedExit")]:
        value = re.search(r"data class " + variant + r"\(.*?override val code: Int = ([0-9]+)", source, re.DOTALL)
        if value is None or int(value[1]) != process[key]:
            raise Rejected(Cause.SOURCE_IDENTITY_MISMATCH)
    keys = set()
    for relative in manifest["configurationSources"]:
        text = (root / relative).read_text()
        observed = set(re.findall(r'"(KAST_[A-Z_]+)"', text))
        if not observed:
            raise Rejected(Cause.INVALID_DOCUMENT)
        keys.update(observed)
    process["configurationKeys"] = sorted(keys)
    return process


def capture(root: Path, kast: Path, schema_file: Path, version: str) -> dict:
    Version.parse(version)
    head = run(["git", "rev-parse", "HEAD"], cwd=root).strip()
    if run(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=root).strip():
        raise Rejected(Cause.SOURCE_DIRTY)
    if run([str(kast), "--version"], cwd=root).strip() != f"kast {version} (IntelliJ sidecar)":
        raise Rejected(Cause.SOURCE_IDENTITY_MISMATCH)
    installed_schema = json.loads(run([str(kast), "--schema"], cwd=root))
    schema = read(schema_file)
    if installed_schema != schema:
        raise Rejected(Cause.SOURCE_IDENTITY_MISMATCH)
    manifest = read(root / "distribution/release/state-contract.json")
    state = {}
    for name, paths in manifest["owners"].items():
        selected = set()
        for pattern in paths:
            matches = list(root.glob(pattern))
            if not matches or any(path.is_symlink() or not path.is_file() for path in matches):
                raise Rejected(Cause.INVALID_DOCUMENT)
            selected.update(matches)
        selected = sorted(selected)
        state[name] = {str(path.relative_to(root)): digest(path.read_bytes()) for path in selected}
    process = process_surface(root, manifest)
    output = {"schemaVersion": 1, "productVersion": version, "sourceRevision": head,
              "contract": {"schema": schema, "commands": command_surface(kast, schema, root),
                           "persistedState": state, "processContract": process},
              "inputs": {"schemaDigest": digest(schema_file.read_bytes()), "stateManifestDigest": digest(canonical(manifest))}}
    return snapshot(output)


def verify_release(candidate_path: Path, repository: str, allow_next_major: bool) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise Rejected(Cause.INVALID_DOCUMENT)
    candidate = snapshot(read(candidate_path))
    version = Version.parse(candidate["productVersion"])
    try:
        pages = json.loads(run(["gh", "api", "--paginate", "--slurp", f"repos/{repository}/releases"]))
    except ValueError as failure:
        raise Rejected(Cause.RELEASE_LOOKUP_FAILED) from failure
    if not isinstance(pages, list) or not all(isinstance(page, list) for page in pages):
        raise Rejected(Cause.RELEASE_LOOKUP_FAILED)
    releases = [release for page in pages for release in page]
    baseline_release = latest_stable(releases, version.major) or latest_stable(releases)
    baseline_identity: dict = {"state": "absent"}
    if baseline_release is None:
        if version.major == 0:
            result = {"status": "pre-stable", "candidateVersion": str(version), "changes": []}
        elif version == Version(1, 0, 0):
            result = {"status": "first-stable", "candidateVersion": str(version), "changes": []}
        else:
            raise Rejected(Cause.FIRST_STABLE_VERSION_INVALID)
    else:
        tag = baseline_release["tag_name"]
        name = f"kast-compatibility-{tag}.json"
        assets = [asset for asset in baseline_release.get("assets", []) if asset.get("name") == name]
        if baseline_release.get("immutable") is not True or len(assets) != 1:
            raise Rejected(Cause.BASELINE_UNAVAILABLE)
        expected = assets[0].get("digest", "")
        if not isinstance(expected, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", expected):
            raise Rejected(Cause.BASELINE_IDENTITY_MISMATCH)
        with tempfile.TemporaryDirectory(prefix="kast-compatibility-") as directory:
            run(["gh", "release", "download", tag, "--repo", repository, "--pattern", name, "--dir", directory])
            baseline_path = Path(directory) / name
            if not baseline_path.is_file() or digest(baseline_path.read_bytes()) != expected:
                raise Rejected(Cause.BASELINE_IDENTITY_MISMATCH)
            previous = snapshot(read(baseline_path))
            if previous["productVersion"] != tag[1:]:
                raise Rejected(Cause.BASELINE_IDENTITY_MISMATCH)
            result = compare(previous, candidate, allow_next_major=allow_next_major)
        baseline_identity = {"state": "observed", "tag": tag, "asset": name, "digest": expected}
    return {"schemaVersion": 1, "status": "passed", "sourceRevision": candidate["sourceRevision"],
            "productVersion": str(version), "candidateDigest": digest(candidate_path.read_bytes()),
            "releaseCatalogDigest": digest(canonical(releases)), "baseline": baseline_identity, "comparison": result}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    capture_parser = actions.add_parser("capture")
    capture_parser.add_argument("--root", type=Path, required=True)
    capture_parser.add_argument("--kast", type=Path, required=True)
    capture_parser.add_argument("--schema", type=Path, required=True)
    capture_parser.add_argument("--version", required=True)
    capture_parser.add_argument("--output", type=Path, required=True)
    verify = actions.add_parser("verify")
    verify.add_argument("--candidate", type=Path, required=True)
    verify.add_argument("--repository", default="amichne/kast")
    verify.add_argument("--allow-next-major", action="store_true")
    verify.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == "capture":
            args.output.unlink(missing_ok=True)
            write(args.output, capture(args.root.resolve(), args.kast.resolve(), args.schema, args.version))
        else:
            args.receipt.unlink(missing_ok=True)
            write(args.receipt, verify_release(args.candidate, args.repository, args.allow_next_major))
    except (Rejected, OSError, ValueError, KeyError, TypeError) as failure:
        cause = failure.cause if isinstance(failure, Rejected) else Cause.INVALID_DOCUMENT
        report = {"status": "rejected", "cause": cause.value}
        if isinstance(failure, Rejected) and failure.changes:
            report["changes"] = [change[:1024] for change in failure.changes[:128]]
            report["totalChanges"] = len(failure.changes)
        print(json.dumps(report, sort_keys=True))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
