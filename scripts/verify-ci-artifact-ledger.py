#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
GIT_SHA_RE = re.compile(r"^[0-9a-f]{7,40}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_text(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        fail(f"{field_name} must be non-empty text")
    return value


def validate_git_sha(value: str, field_name: str) -> None:
    if not GIT_SHA_RE.fullmatch(value):
        fail(f"{field_name} must be a 7 to 40 character lowercase git SHA")


def load_ledger(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        fail(f"{path} is not valid JSON: {error}")
    if not isinstance(payload, dict):
        fail(f"{path} must contain a JSON object")
    return payload


def validate_ledger(payload: dict[str, Any], *, source: Path) -> list[dict[str, str]]:
    if payload.get("schemaVersion") != SCHEMA_VERSION:
        fail(f"{source} schemaVersion must be {SCHEMA_VERSION}")

    git_sha = require_text(payload.get("gitSha"), "gitSha")
    validate_git_sha(git_sha, "gitSha")
    require_text(payload.get("sourceRef"), "sourceRef")
    require_text(payload.get("workflowRunId"), "workflowRunId")

    artifacts = payload.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        fail(f"{source} artifacts must be a non-empty array")

    seen: set[str] = set()
    validated: list[dict[str, str]] = []
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            fail(f"{source} artifacts[{index}] must be an object")
        entry = {
            "artifactKind": require_text(artifact.get("artifactKind"), f"artifacts[{index}].artifactKind"),
            "artifactName": require_text(artifact.get("artifactName"), f"artifacts[{index}].artifactName"),
            "sha256": require_text(artifact.get("sha256"), f"artifacts[{index}].sha256"),
            "producerJob": require_text(artifact.get("producerJob"), f"artifacts[{index}].producerJob"),
            "buildCommandId": require_text(artifact.get("buildCommandId"), f"artifacts[{index}].buildCommandId"),
        }
        if not SHA256_RE.fullmatch(entry["sha256"]):
            fail(f"{source} artifacts[{index}].sha256 must be a lowercase SHA-256 digest")
        if entry["artifactKind"] in seen:
            fail(f"{source} has duplicate artifactKind {entry['artifactKind']}")
        seen.add(entry["artifactKind"])
        validated.append(entry)
    return validated


def parse_artifact_mapping(raw: str) -> tuple[str, Path]:
    if "=" not in raw:
        fail(f"artifact mapping must be KIND=PATH: {raw}")
    kind, path = raw.split("=", 1)
    if not kind.strip() or not path.strip():
        fail(f"artifact mapping must be KIND=PATH: {raw}")
    return kind, Path(path)


def command_record(args: argparse.Namespace) -> None:
    artifact_path = Path(args.artifact_path)
    if not artifact_path.is_file():
        fail(f"artifact path not found: {artifact_path}")

    git_sha = args.git_sha
    validate_git_sha(git_sha, "gitSha")

    payload = {
        "schemaVersion": SCHEMA_VERSION,
        "gitSha": git_sha,
        "sourceRef": args.source_ref,
        "workflowRunId": args.workflow_run_id,
        "artifacts": [
            {
                "artifactKind": args.artifact_kind,
                "artifactName": args.artifact_name,
                "sha256": sha256_file(artifact_path),
                "producerJob": args.producer_job,
                "buildCommandId": args.build_command_id,
            }
        ],
    }
    validate_ledger(payload, source=Path(args.output))

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def command_verify(args: argparse.Namespace) -> None:
    by_kind: dict[str, dict[str, str]] = {}
    source_by_kind: dict[str, Path] = {}
    ledger_git_shas: set[str] = set()

    for ledger_name in args.ledger:
        ledger_path = Path(ledger_name)
        payload = load_ledger(ledger_path)
        entries = validate_ledger(payload, source=ledger_path)
        ledger_git_shas.add(payload["gitSha"])
        for entry in entries:
            kind = entry["artifactKind"]
            if kind in by_kind:
                fail(f"duplicate artifactKind {kind} in {source_by_kind[kind]} and {ledger_path}")
            by_kind[kind] = entry
            source_by_kind[kind] = ledger_path

    if args.git_sha:
        validate_git_sha(args.git_sha, "gitSha")
        unexpected = sorted(sha for sha in ledger_git_shas if sha != args.git_sha)
        if unexpected:
            fail(f"ledger gitSha does not match {args.git_sha}: {unexpected}")

    for required_kind in args.require_kind:
        if required_kind not in by_kind:
            fail(f"missing required artifactKind {required_kind}")

    for raw_mapping in args.artifact:
        kind, path = parse_artifact_mapping(raw_mapping)
        entry = by_kind.get(kind)
        if entry is None:
            fail(f"artifactKind {kind} has no ledger entry")
        if not path.is_file():
            fail(f"artifact file not found for {kind}: {path}")
        actual_sha = sha256_file(path)
        if actual_sha != entry["sha256"]:
            fail(
                f"sha256 mismatch for {kind}: "
                f"expected {entry['sha256']} from {source_by_kind[kind]}, got {actual_sha}"
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Record and verify CI artifact ledgers for single-producer build workflows."
    )
    subcommands = parser.add_subparsers(dest="command", required=True)

    record = subcommands.add_parser("record", help="Write a ledger for one built artifact")
    record.add_argument("--output", required=True)
    record.add_argument("--git-sha", required=True)
    record.add_argument("--source-ref", required=True)
    record.add_argument("--workflow-run-id", required=True)
    record.add_argument("--artifact-kind", required=True)
    record.add_argument("--artifact-name", required=True)
    record.add_argument("--artifact-path", required=True)
    record.add_argument("--producer-job", required=True)
    record.add_argument("--build-command-id", required=True)
    record.set_defaults(func=command_record)

    verify = subcommands.add_parser("verify", help="Validate ledgers and artifact digests")
    verify.add_argument("--ledger", action="append", required=True)
    verify.add_argument("--git-sha")
    verify.add_argument("--require-kind", action="append", default=[])
    verify.add_argument("--artifact", action="append", default=[], help="KIND=PATH")
    verify.set_defaults(func=command_verify)

    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
