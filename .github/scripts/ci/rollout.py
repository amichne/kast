#!/usr/bin/env python3
"""Replay the frozen changes in a disposable CI checkout and preserve timing evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import time
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path

import source_validation
import verification_plan
from validation_scope import DOCUMENTATION_CHECKS, ROOT, select


class Mode(str, Enum):
    WARM = "warm"
    CHANGED = "changed"
    REUSE = "reuse"


@dataclass(frozen=True)
class Case:
    name: str
    before: str
    after: str
    paths: tuple[str, ...]
    sha256: str


@dataclass(frozen=True)
class Replay:
    case: Case
    comparisonBase: str
    sourceRevision: str
    sourceTree: str
    qualifiedTree: str


@dataclass(frozen=True)
class Measurement:
    mode: Mode
    replay: Replay
    startedAt: float
    finishedAt: float
    elapsedMilliseconds: int
    exitCode: int
    source: source_validation.Evidence | None


def corpus(raw: bytes) -> tuple[Case, ...]:
    document: object = json.loads(raw, object_pairs_hook=verification_plan.unique_object)
    if not verification_plan.sequence(document):
        raise ValueError("INVALID_CORPUS")
    result: list[Case] = []
    for row in document:
        if not verification_plan.mapping(row) or set(row) != {
            "name",
            "before",
            "after",
            "paths",
            "sha256",
        }:
            raise ValueError("INVALID_CORPUS")
        name, before, after, digest = (row[key] for key in ("name", "before", "after", "sha256"))
        paths = verification_plan.strings(row["paths"])
        if (
            not isinstance(name, str)
            or not isinstance(before, str)
            or not isinstance(after, str)
            or not isinstance(digest, str)
            or paths is None
        ):
            raise ValueError("INVALID_CORPUS")
        if (
            not paths
            or len(set(paths)) != len(paths)
            or not re.fullmatch(r"[0-9a-f]{64}", digest)
            or any(not re.fullmatch(r"[0-9a-f]{40}", value) for value in (before, after))
            or any(
                not re.fullmatch(r"[A-Za-z0-9_./-]+", path)
                or any(part in {"", ".", ".."} for part in path.split("/"))
                for path in paths
            )
        ):
            raise ValueError("INVALID_CORPUS")
        result.append(Case(name, before, after, paths, digest))
    if {case.name for case in result} != {
        "documentation",
        "leaf-service",
        "semantic-adapter",
        "shared-contract",
    } or len(result) != 4:
        raise ValueError("INVALID_CORPUS")
    return tuple(result)


def git(
    root: Path,
    *arguments: str,
    stdin: bytes | None = None,
    environment: dict[str, str] | None = None,
) -> bytes:
    return subprocess.check_output(["git", *arguments], cwd=root, input=stdin, env=environment)


def prepare(root: Path, case: Case) -> Replay:
    # The runner owns this checkout. Never rewrite a local user's repository for a measurement.
    if (
        os.environ.get("GITHUB_ACTIONS") != "true"
        or root.resolve() != Path(os.environ["GITHUB_WORKSPACE"]).resolve()
    ):
        raise ValueError("DISPOSABLE_CI_CHECKOUT_REQUIRED")
    _, qualified_tree = source_validation.source_identity(root)
    commit_date = git(root, "show", "-s", "--format=%cI", "HEAD").decode().strip()
    environment = os.environ | {"GIT_AUTHOR_DATE": commit_date, "GIT_COMMITTER_DATE": commit_date}
    patch = git(root, "diff", "--binary", case.before, case.after, "--", *case.paths)
    if hashlib.sha256(patch).hexdigest() != case.sha256:
        raise ValueError("CORPUS_PATCH_CHANGED")
    changed = tuple(
        git(root, "diff", "--name-only", case.before, case.after, "--", *case.paths)
        .decode()
        .splitlines()
    )
    if set(changed) != set(case.paths):
        raise ValueError("CORPUS_PATHS_CHANGED")
    git(root, "apply", "--reverse", "--check", "-", stdin=patch)
    git(root, "apply", "--reverse", "--index", "-", stdin=patch)
    identity = (
        "-c",
        "user.name=Kast CI rollout",
        "-c",
        "user.email=ci@example.invalid",
        "-c",
        "core.hooksPath=/dev/null",
    )
    git(root, *identity, "commit", "-qm", f"Rollout before {case.name}", environment=environment)
    before = git(root, "rev-parse", "HEAD").decode().strip()
    git(root, "apply", "--index", "-", stdin=patch)
    git(root, *identity, "commit", "-qm", f"Rollout after {case.name}", environment=environment)
    revision, tree = source_validation.source_identity(root)
    if tree != qualified_tree:
        raise ValueError("REPLAY_TREE_CHANGED")
    return Replay(case, before, revision, tree, qualified_tree)


def forced_outputs(plan: verification_plan.Plan) -> str:
    owners = ", ".join(json.dumps(owner) for owner in plan.consumers)
    # This disposable init script forces affected compilation and tests to execute.
    # Unaffected outputs can use the task cache. Fixture archives keep normal input identity.
    return f"""import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.testing.Test
val affected = setOf({owners})
gradle.beforeProject {{
    if (path in affected) {{
        val ownedBuild = layout.buildDirectory.get().asFile.toPath()
        tasks.configureEach {{
            if (this is Test || this is AbstractCompile || name.startsWith("compile") && name.endsWith("Kotlin")) {{
                outputs.upToDateWhen {{ false }}
                outputs.doNotCacheIf("Frozen rollout needs affected outputs to execute") {{ true }}
                doFirst {{
                    outputs.files.forEach {{ output ->
                        require(output.toPath().startsWith(ownedBuild)) {{ "Unowned rollout output: $output" }}
                        if (output.exists() && !output.deleteRecursively()) error("Cannot remove rollout output: $output")
                    }}
                }}
            }}
        }}
    }}
}}
"""


def documentation(root: Path, destination: Path) -> int:
    commands = (
        ("python3", ".github/scripts/ci/verify_documentation.py"),
        (
            "npm",
            "ci",
            "--prefix",
            ".github/tools/mintlify",
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
        ),
        ("node", "--test", ".github/tools/mintlify/test-runtime.mjs"),
        (str(root / ".github/tools/mintlify/node_modules/.bin/mint"), "validate"),
    )
    with (destination / "documentation.log").open("w") as output:
        for command in commands:
            process = subprocess.run(
                command,
                cwd=root / "docs/public" if command[-1] == "validate" else root,
                stdout=output,
                stderr=subprocess.STDOUT,
                check=False,
            )
            if process.returncode:
                return process.returncode
    return 0


def run(root: Path, case: Case, mode: Mode) -> Measurement:
    started = time.time()
    replay = prepare(root, case)
    destination = root / "build/reports/ci/rollout" / case.name / mode.value
    destination.mkdir(parents=True, exist_ok=True)
    selection = select(
        "pull_request", replay.comparisonBase, replay.sourceRevision, root, affected=True
    )
    evidence = None
    if selection.checks == DOCUMENTATION_CHECKS:
        code = documentation(root, destination)
    else:
        plan = selection.plan
        if plan is None:
            raise ValueError("CORPUS_SELECTION_REJECTED")
        arguments: tuple[str, ...] = ()
        if mode == Mode.CHANGED:
            script = destination / "changed-outputs.gradle.kts"
            script.write_text(forced_outputs(plan))
            arguments = ("--init-script", str(script))
        evidence = source_validation.run(
            root, destination, None if mode == Mode.WARM else plan, arguments
        )
        code = evidence.exitCode
    finished = time.time()
    result = Measurement(
        mode, replay, started, finished, round((finished - started) * 1000), code, evidence
    )
    (destination / "measurement.json").write_text(json.dumps(asdict(result), indent=2) + "\n")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", required=True)
    parser.add_argument("--mode", choices=tuple(Mode), required=True)
    args = parser.parse_args()
    cases = corpus((ROOT / "config/ci/rollout-corpus.json").read_bytes())
    selected = [case for case in cases if case.name == args.case]
    if len(selected) != 1:
        raise SystemExit("UNKNOWN_CORPUS_CASE")
    result = run(ROOT, selected[0], Mode(args.mode))
    print(json.dumps(asdict(result), indent=2))
    raise SystemExit(result.exitCode)


if __name__ == "__main__":
    main()
