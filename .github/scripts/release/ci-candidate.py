#!/usr/bin/env python3
"""Reuse an exact, successful main-CI release candidate or admit a locally built one."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlencode


VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
REVISION = re.compile(r"[0-9a-f]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")


class CandidateError(Exception):
    pass


def github_json(endpoint: str) -> dict:
    result = subprocess.run(["gh", "api", endpoint], capture_output=True, text=True, timeout=60, check=False)
    if result.returncode:
        raise CandidateError("GitHub artifact observation failed")
    try:
        value = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise CandidateError("GitHub artifact observation was malformed") from error
    if not isinstance(value, dict):
        raise CandidateError("GitHub artifact observation was not an object")
    return value


def candidate_artifact(
    listing: dict,
    repository: str,
    revision: str,
    run_observation,
) -> dict | None:
    artifacts = listing.get("artifacts")
    count = listing.get("total_count")
    if (
        not isinstance(artifacts, list)
        or type(count) is not int
        or count < 0
        or count > len(artifacts)
        or any(not isinstance(artifact, dict) or type(artifact.get("id")) is not int for artifact in artifacts)
    ):
        raise CandidateError("GitHub artifact listing was incomplete")
    for artifact in sorted(artifacts, key=lambda item: item.get("id", 0), reverse=True):
        if not isinstance(artifact, dict) or artifact.get("expired") is not False:
            continue
        provenance = artifact.get("workflow_run")
        if not isinstance(provenance, dict) or provenance.get("head_sha") != revision:
            continue
        if provenance.get("head_branch") != "main":
            continue
        run_id = provenance.get("id")
        if type(run_id) is not int:
            raise CandidateError("candidate workflow identity was malformed")
        run = run_observation(run_id)
        if (
            run.get("path") == ".github/workflows/ci.yml"
            and run.get("event") == "push"
            and run.get("head_sha") == revision
            and run.get("head_branch") == "main"
            and run.get("status") == "completed"
            and run.get("conclusion") == "success"
            and isinstance(run.get("repository"), dict)
            and run["repository"].get("full_name") == repository
        ):
            return artifact
    return None


def digest(path: Path) -> str:
    if not path.is_file() or path.is_symlink():
        raise CandidateError(f"candidate file is missing or not regular: {path.name}")
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate(directory: Path, version: str, revision: str) -> None:
    if not directory.is_dir() or directory.is_symlink():
        raise CandidateError("candidate directory is missing or not regular")
    plugins = tuple(directory.glob(f"kast-ide-hosted-v{version}-idea-*.zip"))
    if len(plugins) != 1 or re.fullmatch(rf"kast-ide-hosted-v{re.escape(version)}-idea-[0-9]+\.zip", plugins[0].name) is None:
        raise CandidateError("candidate must contain one matching hosted plugin")
    archives = (
        f"kast-control-v{version}-macos-aarch64.tar.gz",
        plugins[0].name,
    )
    base_names = archives + (
        f"kast-hosted-catalog-v{version}.json",
        f"kast-module-knowledge-v{version}.json",
        f"kast-sbom-v{version}.cdx.json",
    )
    expected = set(base_names) | {name + ".sha256" for name in base_names}
    observed = {file.name for file in directory.iterdir()}
    if observed != expected:
        raise CandidateError("candidate asset inventory does not match the release contract")
    hashes = {name: digest(directory / name) for name in base_names}
    for name, sha in hashes.items():
        checksum = directory / (name + ".sha256")
        digest(checksum)
        if checksum.read_text(encoding="utf-8") != f"{sha}  {name}\n":
            raise CandidateError(f"candidate checksum does not match: {name}")
    try:
        sbom = json.loads((directory / f"kast-sbom-v{version}.cdx.json").read_text(encoding="utf-8"))
        properties = sbom["metadata"]["properties"]
        recorded = {item["name"]: item["value"] for item in properties}
    except (KeyError, TypeError, ValueError) as error:
        raise CandidateError("candidate SBOM metadata is malformed") from error
    if len(recorded) != len(properties):
        raise CandidateError("candidate SBOM metadata is ambiguous")
    if sbom.get("bomFormat") != "CycloneDX" or recorded.get("kast:source-revision") != revision:
        raise CandidateError("candidate SBOM source identity does not match")
    for name in archives:
        if recorded.get(f"kast:archive:{name}") != f"sha256:{hashes[name]}":
            raise CandidateError(f"candidate SBOM archive identity does not match: {name}")


def reuse(repository: str, version: str, revision: str, directory: Path) -> str:
    name = f"ci-release-candidate-v{version}-{revision}"
    query = urlencode({"name": name, "per_page": 100})
    listing = github_json(f"repos/{repository}/actions/artifacts?{query}")
    artifact = candidate_artifact(
        listing,
        repository,
        revision,
        lambda run_id: github_json(f"repos/{repository}/actions/runs/{run_id}"),
    )
    if artifact is None:
        return "MISSING"
    if artifact.get("name") != name:
        raise CandidateError("candidate artifact name does not match")
    if directory.exists() and any(directory.iterdir()):
        raise CandidateError("candidate destination is not empty")
    directory.mkdir(parents=True, exist_ok=True)
    run_id = artifact["workflow_run"]["id"]
    result = subprocess.run(
        ["gh", "run", "download", str(run_id), "--repo", repository, "-n", name, "-D", str(directory)],
        check=False,
        timeout=180,
    )
    if result.returncode:
        raise CandidateError("candidate artifact download failed")
    validate(directory, version, revision)
    return "REUSED"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("reuse", "validate"))
    parser.add_argument("--repository", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    if not REPOSITORY.fullmatch(args.repository) or not VERSION.fullmatch(args.version) or not REVISION.fullmatch(args.source_revision):
        raise SystemExit("release-candidate: invalid repository, version, or source revision")
    try:
        if args.action == "reuse":
            state = reuse(args.repository, args.version, args.source_revision, args.directory)
            output = os.environ.get("GITHUB_OUTPUT")
            if output:
                with Path(output).open("a", encoding="utf-8") as destination:
                    destination.write(f"candidate={state}\n")
            print(f"release-candidate: {state}")
        else:
            validate(args.directory, args.version, args.source_revision)
            print("release-candidate: admitted")
    except (CandidateError, OSError, subprocess.TimeoutExpired) as error:
        raise SystemExit(f"release-candidate: rejected: {error}") from None


if __name__ == "__main__":
    main()
